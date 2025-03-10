/**
 * ShinyProxy-Operator
 *
 * Copyright (C) 2021 Open Analytics
 *
 * ===========================================================================
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the Apache License as published by
 * The Apache Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * Apache License for more details.
 *
 * You should have received a copy of the Apache License
 * along with this program.  If not, see <http://www.apache.org/licenses/>
 */
package eu.openanalytics.shinyproxyoperator

import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import eu.openanalytics.shinyproxyoperator.controller.IReconcileListener
import eu.openanalytics.shinyproxyoperator.controller.PodRetriever
import eu.openanalytics.shinyproxyoperator.controller.ResourceListener
import eu.openanalytics.shinyproxyoperator.controller.ResourceRetriever
import eu.openanalytics.shinyproxyoperator.controller.ShinyProxyController
import eu.openanalytics.shinyproxyoperator.controller.ShinyProxyEvent
import eu.openanalytics.shinyproxyoperator.controller.ShinyProxyListener
import eu.openanalytics.shinyproxyoperator.crd.ShinyProxy
import eu.openanalytics.shinyproxyoperator.ingress.skipper.IngressController
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.ConfigMapList
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.ServiceList
import io.fabric8.kubernetes.api.model.apps.ReplicaSet
import io.fabric8.kubernetes.api.model.apps.ReplicaSetList
import io.fabric8.kubernetes.client.DefaultKubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import io.fabric8.kubernetes.client.NamespacedKubernetesClient
import io.fabric8.kubernetes.client.dsl.Resource
import io.fabric8.kubernetes.client.dsl.RollableScalableResource
import io.fabric8.kubernetes.client.dsl.ServiceResource
import io.fabric8.kubernetes.client.informers.cache.Lister
import io.fabric8.kubernetes.client.utils.Serialization
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import mu.KotlinLogging
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.core.config.Configurator
import java.util.*
import kotlin.concurrent.schedule
import kotlin.system.exitProcess


class Operator(client: NamespacedKubernetesClient? = null,
               mode: Mode? = null,
               disableSecureCookies: Boolean? = null,
               reconcileListener: IReconcileListener? = null,
               probeInitialDelay: Int? = null,
               probeFailureThreshold: Int? = null,
               probeTimeout: Int? = null,
               startupProbeInitialDelay: Int? = null,
               logLevel: Level? = null) {

    private val logger = KotlinLogging.logger {}
    private val client: NamespacedKubernetesClient
    val mode: Mode
    val namespace: String
    val disableSecureCookies: Boolean
    val probeInitialDelay: Int
    val probeFailureThreshold: Int
    val probeTimeout: Int
    val startupProbeInitialDelay: Int
    val processMaxLifetime: Long

    val podRetriever: PodRetriever
    private val shinyProxyClient: ShinyProxyClient

    private val shinyProxyListener: ShinyProxyListener
    private val replicaSetListener: ResourceListener<ReplicaSet, ReplicaSetList, RollableScalableResource<ReplicaSet>>
    private val serviceListener: ResourceListener<Service, ServiceList, ServiceResource<Service>>
    private val configMapListener: ResourceListener<ConfigMap, ConfigMapList, Resource<ConfigMap>>
    private val ingressController: IngressController

    private val channel = Channel<ShinyProxyEvent>(10000)
    val sendChannel: SendChannel<ShinyProxyEvent> = channel // public for tests

    /**
     * Initialize mode, client, namespace and listeners
     */
    init {
        Serialization.jsonMapper().registerKotlinModule()
        Serialization.yamlMapper().registerKotlinModule()
        if (client != null) {
            this.client = client
        } else {
            this.client = DefaultKubernetesClient()
        }

        this.mode = readConfigValue(mode, Mode.CLUSTERED, "SPO_MODE") {
            when (it.lowercase(Locale.getDefault())) {
                "clustered" -> Mode.CLUSTERED
                "namespaced" -> Mode.NAMESPACED
                else -> error("Unsupported operator mode: $it")
            }
        }
        this.disableSecureCookies = readConfigValue(disableSecureCookies, false, "SPO_DISABLE_SECURE_COOKIES") { true }
        this.probeInitialDelay = readConfigValue(probeInitialDelay, 0, "SPO_PROBE_INITIAL_DELAY", String::toInt)
        this.probeFailureThreshold = readConfigValue(probeFailureThreshold, 0, "SPO_PROBE_FAILURE_THRESHOLD", String::toInt)
        this.probeTimeout = readConfigValue(probeTimeout, 1, "SPO_PROBE_TIMEOUT", String::toInt)
        this.startupProbeInitialDelay = readConfigValue(startupProbeInitialDelay, 60, "SPO_STARTUP_PROBE_INITIAL_DELAY", String::toInt)
        this.processMaxLifetime = readConfigValue(null, -1, "SPO_PROCESS_MAX_LIFETIME", String::toLong)
        if (this.processMaxLifetime != -1L) {
            Timer().schedule(this.processMaxLifetime * 60 * 1000) {
                logger.warn { "Max lifetime of process reached, preparing shutdown" }
                sendChannel.close()
                while (!channel.isClosedForReceive && !channel.isEmpty && !shinyProxyController.idle) {
                    logger.warn { "Still processing events in queue, delaying shutdown" }
                    Thread.sleep(250)
                }
                logger.warn { "Queue is empty, exiting process" }
                exitProcess(1)
            }
        }

        val level = readConfigValue(logLevel, Level.DEBUG, "SPO_LOG_LEVEL") { Level.toLevel(it) }
        Configurator.setRootLevel(level)

        logger.info { "Running in ${this.mode} mode" }

        namespace = if (this.client.namespace == null) {
            logger.info { "No namespace found via config, assuming default." }
            "default"
        } else {
            this.client.namespace
        }
        logger.info { "Using namespace : $namespace " }

        this.shinyProxyClient = when (this.mode) {
            Mode.CLUSTERED -> this.client.inAnyNamespace().resources(ShinyProxy::class.java)
            Mode.NAMESPACED -> this.client.inNamespace(namespace).resources(ShinyProxy::class.java)
        }

        shinyProxyListener = ShinyProxyListener(sendChannel, this.shinyProxyClient)
        podRetriever = PodRetriever(this.client)

        if (this.mode == Mode.CLUSTERED) {
            replicaSetListener = ResourceListener(sendChannel, this.client.inAnyNamespace().apps().replicaSets())
            serviceListener = ResourceListener(sendChannel, this.client.inAnyNamespace().services())
            configMapListener = ResourceListener(sendChannel, this.client.inAnyNamespace().configMaps())
            ingressController = IngressController(sendChannel, this.client, this.client.inAnyNamespace().network().ingress())
        } else {
            replicaSetListener = ResourceListener(sendChannel, this.client.inNamespace(namespace).apps().replicaSets())
            serviceListener = ResourceListener(sendChannel, this.client.inNamespace(namespace).services())
            configMapListener = ResourceListener(sendChannel, this.client.inNamespace(namespace).configMaps())
            ingressController = IngressController(sendChannel, this.client, this.client.inNamespace(namespace).network().ingress())
        }
    }

    /**
     * Controllers
     */
    val shinyProxyController = ShinyProxyController(channel, this.client, shinyProxyClient, ingressController, podRetriever, reconcileListener)

    fun prepare(): Pair<ResourceRetriever, Lister<ShinyProxy>> {
        logger.info { "Starting background processes of ShinyProxy Operator" }
        try {
            if (client.apiextensions().v1().customResourceDefinitions().withName("shinyproxies.openanalytics.eu").get() == null) {
                println()
                println()
                println("ERROR: the CustomResourceDefinition (CRD) of the Operator does not exist!")
                println("The name of the CRD is 'shinyproxies.openanalytics.eu'")
                println("Create the CRD first, before starting the operator")
                println()
                println("Exiting in 10 seconds because of the above error")
                Thread.sleep(10000) // sleep 10 seconds to make it easier to find this error by an sysadmin

                exitProcess(2)
            }
        } catch (e: KubernetesClientException) {
            println()
            println()
            println("Warning: could not check whether ShinyProxy CRD exits.")
            println("This is normal when the ServiceAccount of the operator does not have permission to access CRDs (at cluster scope).")
            println("If you get an unexpected error after this message, make sure that the CRD exists.")
            println()
            println()
        }

        val shinyProxyLister = Lister(shinyProxyListener.start())
        val replicaSetLister = Lister(replicaSetListener.start(shinyProxyLister))
        val serviceLister = Lister(serviceListener.start(shinyProxyLister))
        val configMapLister = Lister(configMapListener.start(shinyProxyLister))
        val ingressLister = Lister(ingressController.start(shinyProxyLister))
        val resourceRetriever = ResourceRetriever(replicaSetLister, configMapLister, serviceLister, ingressLister)

        return resourceRetriever to shinyProxyLister
    }

    suspend fun run(resourceRetriever: ResourceRetriever, shinyProxyLister: Lister<ShinyProxy>) {
        logger.info { "Starting ShinyProxy Operator" }
        shinyProxyController.run(resourceRetriever, shinyProxyLister)
    }

    fun stop() {
        shinyProxyListener.stop()
        replicaSetListener.stop()
        serviceListener.stop()
        configMapListener.stop()
        ingressController.stop()
    }

    companion object {
        private var _operatorInstance: Operator? = null

        fun setOperatorInstance(operator: Operator) {
            this._operatorInstance = operator
        }

        fun getOperatorInstance(): Operator {
            _operatorInstance.let {
                if (it == null) {
                    throw IllegalStateException("Cannot query for operatorInstance when it is not set")
                } else {
                    return it
                }
            }
        }
    }

    private fun <T> readConfigValue(constructorValue: T?, default: T, envVarName: String, convertor: (String) -> T): T {
        val e = System.getenv(envVarName)
        val res = when {
            constructorValue != null -> constructorValue
            e != null -> convertor(e)
            else -> default
        }
        logger.info { "Using $res for property $envVarName" }
        return res
    }


}

enum class Mode {
    CLUSTERED,
    NAMESPACED
}
