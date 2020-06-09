package org.apache.openwhisk.core.invoker

import java.nio.charset.StandardCharsets

import akka.actor.{ActorRefFactory, ActorSystem}
import org.apache.openwhisk.common.tracing.WhiskTracerProvider
import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.core.connector.{ActivationMessage, CombinedCompletionAndResultMessage, MessageFeed, MessageProducer}
import org.apache.openwhisk.core.containerpool.kubernetes.ActionExecutionMessage.RunWithEphemeralContainer
import org.apache.openwhisk.core.containerpool.kubernetes.EdgeContainerPool
import org.apache.openwhisk.core.containerpool.{ContainerPoolConfig, ContainerProxy}
import org.apache.openwhisk.core.database.{DocumentTypeMismatchException, DocumentUnreadable, NoDocumentException, UserContext}
import org.apache.openwhisk.core.entity.{ActivationResponse, ConcurrencyLimitConfig, DocRevision, FullyQualifiedEntityName, InvokerInstanceId, WhiskAction}
import org.apache.openwhisk.core.{ConfigKeys, WhiskConfig}
import org.apache.openwhisk.http.Messages
import pureconfig._
import pureconfig.generic.auto._

import scala.concurrent.Future

class EdgeInvoker(whiskConfig: WhiskConfig,
                  instance: InvokerInstanceId,
                  producer: MessageProducer,
                  poolConfig: ContainerPoolConfig = loadConfigOrThrow[ContainerPoolConfig](ConfigKeys.containerPool),
                  limitConfig: ConcurrencyLimitConfig = loadConfigOrThrow[ConcurrencyLimitConfig](
                    ConfigKeys.concurrencyLimit))(implicit actorSystem: ActorSystem, logging: Logging)
    extends InvokerReactive(whiskConfig, instance, producer, poolConfig, limitConfig) {

  logging.info(this, s"Initializing $getClass")

  private val systemNamespace: String = "whisk.system"

  private val childFactory = (f: ActorRefFactory) =>
    f.actorOf(ContainerProxy.props(containerFactory.createContainer,
      ack,
      store,
      collectLogs,
      instance,
      poolConfig,
      factoryWithFixedSize = Some(containerFactory.createContainerWithFixedSize)))

  private val pool = actorSystem.actorOf(EdgeContainerPool.props(childFactory))

  override def processActivationMessage(bytes: Array[Byte]): Future[Unit] = {
    Future(ActivationMessage.parse(new String(bytes, StandardCharsets.UTF_8)))
      .flatMap(Future.fromTry)
      .flatMap { msg: ActivationMessage =>
        implicit val transid: TransactionId = msg.transid

        logging.info(this, s"${msg.user.namespace.name} ${msg.containerId}")

        // Set trace context to continue tracing
        WhiskTracerProvider.tracer.setTraceContext(transid, msg.traceContext)

        if (!namespaceBlacklist.isBlacklisted(msg.user)) {
          // Note: a potential pitfall is that the user and action in the same activation can belong to different namespace
          // This should be avoided in experiment by users only running actions in its own namespace
          // TODO: fix this problem later
          val namespace = msg.action.path
          val name = msg.action.name
          val actionId = FullyQualifiedEntityName(namespace, name).toDocId.asDocInfo(msg.revision)
          val subject = msg.user.subject

          val hasRevision: Boolean = (actionId.rev != DocRevision.empty)
          if (!hasRevision) logging.warn(this, s"revision was not provided for ${actionId.id}")

          WhiskAction
            .get(entityStore, actionId.id, actionId.rev, fromCache = hasRevision)
            .flatMap { action =>
              action.toExecutableWhiskAction match {
                case Some(executable) =>
                  // This is a user action and the target container should be specified
                  if (msg.user.namespace.name.asString != systemNamespace) {
                    val containerId = msg.containerId.get
                    Future.failed(new NotImplementedError())
                  } else {
                    pool ! RunWithEphemeralContainer(executable, msg)
                  }
                  Future.successful(())
                case None =>
                  logging.error(this, s"non-executable action reached the invoker: ${action.fullyQualifiedName(false)}")
                  Future.failed(new IllegalArgumentException("non-executable action reached the invoker"))
              }
            }
            .recoverWith {
              case t =>
                val response = t match {
                  case _: NoDocumentException =>
                    ActivationResponse.applicationError(Messages.actionRemovedWhileInvoking)
                  case _: DocumentTypeMismatchException | _: DocumentUnreadable =>
                    ActivationResponse.whiskError(Messages.actionMismatchWhileInvoking)
                  case _ =>
                    ActivationResponse.whiskError(Messages.actionFetchErrorWhileInvoking)
                }
                activationFeed ! MessageFeed.Processed

                val activation = generateFallbackActivation(msg, response)
                ack(
                  msg.transid,
                  activation,
                  msg.blocking,
                  msg.rootControllerIndex,
                  msg.user.namespace.uuid,
                  CombinedCompletionAndResultMessage(transid, activation, instance))

                store(msg.transid, activation, msg.blocking, UserContext(msg.user))
                Future.successful(())
            }
        } else {
          activationFeed ! MessageFeed.Processed

          val activation =
            generateFallbackActivation(msg, ActivationResponse.applicationError(Messages.namespacesBlacklisted))

          ack(
            msg.transid,
            activation,
            msg.blocking,
            msg.rootControllerIndex,
            msg.user.namespace.uuid,
            CombinedCompletionAndResultMessage(transid, activation, instance))

          logging.warn(this, s"namespace ${msg.user.namespace.name} was blocked.")
          Future.successful(())
        }
      }
  }
}

object EdgeInvoker extends InvokerProvider {
  override def instance(
    config: WhiskConfig,
    instance: InvokerInstanceId,
    producer: MessageProducer,
    poolConfig: ContainerPoolConfig,
    limitConfig: ConcurrencyLimitConfig)(implicit actorSystem: ActorSystem, logging: Logging): InvokerCore =
    new EdgeInvoker(config, instance, producer, poolConfig, limitConfig)
}