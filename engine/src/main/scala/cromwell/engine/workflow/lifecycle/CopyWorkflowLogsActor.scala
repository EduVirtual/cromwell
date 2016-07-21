package cromwell.engine.workflow.lifecycle

import java.io.IOException
import java.nio.file.Path

import akka.actor.SupervisorStrategy.Restart
import akka.actor.{Actor, ActorLogging, OneForOneStrategy, Props}
import better.files._
import cromwell.core._
import cromwell.core.logging.WorkflowLogger
import cromwell.database.obj.WorkflowMetadataKeys
import cromwell.services.metadata.{MetadataValue, MetadataEvent, MetadataKey, MetadataService}
import MetadataService.PutMetadataAction
import cromwell.services.ServiceRegistryClient

object CopyWorkflowLogsActor {
  // Commands
  case class Copy(workflowId: WorkflowId, destinationDirPath: Path)

  val strategy = OneForOneStrategy(maxNrOfRetries = 3) {
    case _: IOException => Restart
  }

  def props = Props(new CopyWorkflowLogsActor()).withDispatcher("akka.dispatchers.io-dispatcher")
}

// This could potentially be turned into a more generic "Copy/Move something from A to B"
// Which could be used for other copying work (outputs, call logs..)
class CopyWorkflowLogsActor extends Actor with ActorLogging with PathFactory with ServiceRegistryClient {

  def copyAndClean(src: Path, dest: Path): Unit = {
    dest.parent.createDirectories()

    src.copyTo(dest, overwrite = true)
    if (WorkflowLogger.isTemporary) src.delete()
  }

  override def receive = {
    case CopyWorkflowLogsActor.Copy(workflowId, destinationDir) =>
      val workflowLogger = new WorkflowLogger(self.path.name, workflowId, Option(log))

      workflowLogger.workflowLogPath foreach { src =>
        if (src.exists) {
          val destPath = destinationDir.resolve(src.getFileName)
          workflowLogger.info(s"Copying workflow logs from ${src.toAbsolutePath} to $destPath")

          copyAndClean(src, destPath)

          val metadataEventMsg = MetadataEvent(MetadataKey(workflowId, None, WorkflowMetadataKeys.WorkflowLog), MetadataValue(destPath))
          serviceRegistryActor ! PutMetadataAction(metadataEventMsg)
        }
      }
  }

  override def preRestart(t: Throwable, message: Option[Any]) = {
    message foreach self.forward
  }
}
