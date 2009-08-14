import org.codehaus.groovy.grails.commons.GrailsClassUtils
import grails.util.GrailsUtil
import org.springframework.jms.core.JmsTemplate
import grails.jms.listener.ServiceInspector
import grails.jms.listener.ListenerConfigFactory
import org.springframework.beans.factory.InitializingBean
import org.codehaus.groovy.grails.commons.ServiceArtefactHandler

class JmsGrailsPlugin {
    
    def version = "0.5-RC1"
    def author = "Luke Daley"
    def authorEmail = "ld@ldaley.com"
    def title = "This plugin adds MDB functionality to services."
    
    def loadAfter = ['services', 'controllers']
    def observe = ['services', 'controllers']
    
    static final DEFAULT_CONNECTION_FACTORY_BEAN_NAME = "jmsConnectionFactory"

    def listenerConfigs = [:]
    def serviceInspector = new ServiceInspector()
    def listenerConfigFactory = new ListenerConfigFactory()
    
    def getListenerConfigs(serviceClass, application, log) {
        log.info("inspecting '${serviceClass.name}' for JMS listeners")
        serviceInspector.getListenerConfigs(serviceClass, listenerConfigFactory, application)
    }
    
    def registerListenerConfig(listenerConfig, beanBuilder, log) {
        def methodOrClosure = (listenerConfig.listenerIsClosure) ? "closure" : "method"
        def queueOrTopic = (listenerConfig.topic) ? "TOPIC" : "QUEUE"
        log.info "registering listener for ${methodOrClosure} '${listenerConfig.listenerMethodOrClosureName}' of service '${listenerConfig.serviceBeanPrefix}' to ${queueOrTopic} '${listenerConfig.destinationName}'"
        listenerConfig.register(beanBuilder)
        
    }
    def doWithSpring = {
        application.serviceClasses?.each { service ->
            def serviceClass = service.getClazz()
            def serviceClassListenerConfigs = getListenerConfigs(serviceClass, application, log)
            if (serviceClassListenerConfigs) {
                serviceClassListenerConfigs.each {
                    registerListenerConfig(it, delegate, log)
                }
                listenerConfigs[serviceClass.name] = serviceClassListenerConfigs
            }
        }
        defaultJmsTemplate(org.springframework.jms.core.JmsTemplate, ref(DEFAULT_CONNECTION_FACTORY_BEAN_NAME, true))
    }
    
    def doWithApplicationContext = { applicationContext ->
        listenerConfigs.each { serviceClassName, serviceClassListenerConfigs ->
            serviceClassListenerConfigs.each {
                startListenerContainer(it, applicationContext)
            }
        }
    }
    
    def sendJMSMessage2 = { jmsService, destination, message ->
        jmsService.send(destination, message)
    }
    def sendJMSMessage3 = { jmsService, destination, message, postProcessor ->
        jmsService.send(destination, message, postProcessor)
    }
    def sendJMSMessage4 = { jmsService, destination, message, jmsTemplateBeanName, postProcessor ->
        jmsService.send(destination, message, jmsTemplateBeanName, postProcessor)
    }
    def sendQueueJMSMessage2 = { jmsService, destination, message ->
        jmsService.send(queue: destination, message)
    }
    def sendQueueJMSMessage3 = { jmsService, destination, message, postProcessor ->
        jmsService.send(queue: destination, message, postProcessor)
    }
    def sendQueueJMSMessage4 = { jmsService, destination, message, jmsTemplateBeanName, postProcessor ->
        jmsService.send(queue: destination, message, jmsTemplateBeanName, postProcessor)
    }
    def sendTopicJMSMessage2 = { jmsService, destination, message ->
        jmsService.send(topic: destination, message)
    }
    def sendTopicJMSMessage3 = { jmsService, destination, message, postProcessor ->
        jmsService.send(topic: destination, message, postProcessor)
    }
    def sendTopicJMSMessage4 = { jmsService, destination, message, jmsTemplateBeanName, postProcessor ->
        jmsService.send(topic: destination, message, jmsTemplateBeanName, postProcessor)
    }
    
    def addSendMethodsToClass(jmsService, clazz) {
        [
            sendJMSMessage: "sendJMSMessage", 
            sendQueueJMSMessage: "sendQueueJMSMessage", 
            sendTopicJMSMessage: "sendTopicJMSMessage",
            sendPubSubJMSMessage: "sendTopicJMSMessage"
        ].each { m, i ->
            2.upto(4) { n ->
                clazz.metaClass."$m" << this."$i$n".curry(jmsService)
            } 
        }
    }

    def doWithDynamicMethods = { ctx ->
        def jmsService = ctx.getBean("jmsService")
        [application.controllerClasses, application.serviceClasses].each {
            it.each {
                if (it.clazz.name != "JmsService") {
                    addSendMethodsToClass(jmsService, it.clazz)
                }
            }
        }
    }

    def onChange = { event ->
        if (event.source && event.ctx) {
            def jmsService = event.ctx.getBean('jmsService')
            
            if (application.isControllerClass(event.source)) {
                addSendMethodsToClass(jmsService, event.source)
            } else if (application.isServiceClass(event.source)) {
                boolean isNew = event.application.getServiceClass(event.source?.name) == null
                def serviceClass = application.addArtefact(ServiceArtefactHandler.TYPE, event.source).clazz
                
                if (!isNew) {
                    def serviceListenerConfigs = listenerConfigs.remove(serviceClass.name)
                    serviceListenerConfigs.each {
                        log.info("removing JMS listener beans for ${it.serviceBeanName}.${it.listenerMethodOrClosureName}")
                        it.removeBeansFromContext(event.ctx)
                    }
                }
                
                def serviceListenerConfigs = getListenerConfigs(serviceClass, application, log)
                if (serviceListenerConfigs) {
                    listenerConfigs[serviceClass.name] = serviceListenerConfigs
                    def newBeans = beans {
                        serviceListenerConfigs.each { listenerConfig ->
                            registerListenerConfig(listenerConfig, delegate, log)
                        }
                    }
                    newBeans.beanDefinitions.each { n,d ->
                        event.ctx.registerBeanDefinition(n, d)
                    }
                    serviceListenerConfigs.each {
                        startListenerContainer(it, event.ctx)
                    }
                }
                
                addSendMethodsToClass(jmsService, event.source)
            }
            
        }
    }

    def onApplicationChange = { event ->
    }
    
    def startListenerContainer(listenerConfig, applicationContext) {
        applicationContext.getBean(listenerConfig.listenerContainerBeanName).start()
    }
    
}
