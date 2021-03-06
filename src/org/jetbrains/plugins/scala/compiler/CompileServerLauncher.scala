package org.jetbrains.plugins.scala
package compiler

import com.intellij.openapi.components.ApplicationComponent
import com.intellij.openapi.projectRoots.{JavaSdk, ProjectJdkTable}
import collection.JavaConverters._
import com.intellij.util.PathUtil
import java.io.File
import com.intellij.openapi.application.ApplicationManager
import extensions._
import com.intellij.notification.{Notifications, NotificationType, Notification}
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.project.Project

/**
 * @author Pavel Fatin
 */
class CompileServerLauncher extends ApplicationComponent {
   private var instance: Option[ServerInstance] = None

   def initComponent() {}

   def disposeComponent() {
     if (running) stop()
   }

   def tryToStart(project: Project): Boolean = {
     val applicationSettings = ScalaApplicationSettings.getInstance

     if (applicationSettings.COMPILE_SERVER_SDK == null) {
       // Try to find a suitable JDK
       val choice = Option(ProjectRootManager.getInstance(project).getProjectSdk).orElse {
         val all = ProjectJdkTable.getInstance.getSdksOfType(JavaSdk.getInstance()).asScala
         all.headOption
       }

       choice.foreach(sdk => applicationSettings.COMPILE_SERVER_SDK = sdk.getName)

//       val message = "JVM SDK is automatically selected: " + name +
//               "\n(can be changed in Application Settings / Scala)"
//       Notifications.Bus.notify(new Notification("scala", "Scala compile server",
//         message, NotificationType.INFORMATION))
     }

     javaExecutableIn(applicationSettings.COMPILE_SERVER_SDK) match {
       case Left(error) =>
         val title = "Cannot launch Scala compile server"
         val message = error +
                 "\nPlease either disable Scala compile server or configure a valid JVM SDK for it."
         Notifications.Bus.notify(new Notification("scala", title, message, NotificationType.ERROR))
         false
       case Right(java) =>
         init(java)
         true
     }
   }

   private def init(java: File) {
     if (!running) start(FileUtil.toCanonicalPath(java.getPath))
   }

   private def start(java: String) {
     val settings = ScalaApplicationSettings.getInstance

     val jvmParameters = {

       val xmx = settings.COMPILE_SERVER_MAXIMUM_HEAP_SIZE |> { size =>
         if (size.isEmpty) Nil else List("-Xmx%sm".format(size))
       }

       xmx ++ settings.COMPILE_SERVER_JVM_PARAMETERS.split(" ").toSeq
     }

     val files = {
       val ideaRoot = (new File(PathUtil.getJarPathForClass(classOf[ApplicationManager]))).getParent
       val pluginRoot = (new File(PathUtil.getJarPathForClass(getClass))).getParent
       val jpsRoot = new File(pluginRoot, "jps")

       Seq(
         new File(ideaRoot, "jps-server.jar"),
         new File(ideaRoot, "trove4j.jar"),
         new File(ideaRoot, "util.jar"),
         new File(pluginRoot, "scala-library.jar"),
         new File(pluginRoot, "scala-plugin-runners.jar"),
         new File(jpsRoot, "nailgun.jar"),
         new File(jpsRoot, "sbt-interface.jar"),
         new File(jpsRoot, "incremental-compiler.jar"),
         new File(jpsRoot, "jline.jar"),
         new File(jpsRoot, "scala-jps-plugin.jar"))
     }

     files.partition(_.exists) match {
       case (presentFiles, Seq()) =>
         val classpath = presentFiles.map(_.getPath).mkString(File.pathSeparator)

         val commands = java +: "-cp" +: classpath +: jvmParameters :+
                 "org.jetbrains.plugins.scala.nailgun.NailgunRunner" :+ settings.COMPILE_SERVER_PORT

         val process = new ProcessBuilder(commands.asJava).start()

         val watcher = new ProcessWatcher(process)

         instance = Some(ServerInstance(watcher, settings.COMPILE_SERVER_PORT.toInt))

         watcher.startNotify()

       case (_, absentFiles) =>
         val paths = absentFiles.map(_.getPath).mkString(", ")

         Notifications.Bus.notify(new Notification("scala", "Cannot start Scala compile server",
           "Required file(s) not found: " + paths, NotificationType.ERROR))
     }
   }

   // TODO stop server more gracefully
   def stop() {
     instance.foreach { it =>
       it.destroyProcess()
     }
   }

   def running: Boolean = instance.exists(_.running)

   def errors(): Seq[String] = instance.map(_.errors()).getOrElse(Seq.empty)

   def port: Option[Int] = instance.map(_.port)

   def getComponentName = getClass.getSimpleName
 }

object CompileServerLauncher {
  def instance = ApplicationManager.getApplication.getComponent(classOf[CompileServerLauncher])
}

private case class ServerInstance(watcher: ProcessWatcher, port: Int) {
  def running: Boolean = watcher.running

  def errors(): Seq[String] = watcher.errors()

  def destroyProcess() {
    watcher.destroyProcess()
  }
}