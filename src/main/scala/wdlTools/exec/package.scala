package wdlTools.exec

import java.nio.file.{Files, Path, Paths}

import dx.util.{ExecPaths, FileUtils}
import wdlTools.eval.DefaultEvalPaths
import wdlTools.syntax.SourceLocation

// A runtime error
final class ExecException(message: String) extends Exception(message) {
  def this(msg: String, loc: SourceLocation) = {
    this(ExecException.formatMessage(msg, loc))
  }

  def this(message: String, cause: Throwable, loc: SourceLocation) = {
    this(message, loc)
    initCause(cause)
  }
}

object ExecException {
  def formatMessage(msg: String, loc: SourceLocation): String = {
    s"${msg} at ${loc}"
  }
}

class DefaultExecPaths(rootDir: Path, tempDir: Path)
    extends DefaultEvalPaths(rootDir, tempDir)
    with ExecPaths {
  def getCommandFile(ensureParentExists: Boolean = false): Path = {
    getMetaDir(ensureParentExists).resolve(DefaultExecPaths.DefaultCommandScript)
  }

  def getReturnCodeFile(ensureParentExists: Boolean = false): Path = {
    getMetaDir(ensureParentExists).resolve(DefaultExecPaths.DefaultReturnCode)
  }

  def getContainerCommandFile(ensureParentExists: Boolean = false): Path = {
    getMetaDir(ensureParentExists).resolve(DefaultExecPaths.DefaultContainerRunScript)
  }

  def getContainerIdFile(ensureParentExists: Boolean = false): Path = {
    getMetaDir(ensureParentExists).resolve(DefaultExecPaths.DefaultContainerId)
  }
}

object DefaultExecPaths {
  val DefaultCommandScript = "commandScript"
  val DefaultReturnCode = "returnCode"
  val DefaultContainerRunScript = "containerRunScript"
  val DefaultContainerId = "containerId"

  def apply(executionDir: Path, tempDir: Path): ExecPaths = {
    new DefaultExecPaths(executionDir, tempDir)
  }

  def createLocalPathsFromDir(executionDir: Path = FileUtils.cwd,
                              tempDir: Path = FileUtils.systemTempDir): ExecPaths = {
    if (!Files.isDirectory(executionDir)) {
      throw new ExecException(s"${executionDir} does not exist or is not a directory")
    }
    DefaultExecPaths(executionDir, tempDir)
  }

  def createLocalPathsFromTemp(): ExecPaths = {
    val rootDir = Files.createTempDirectory("wdlTools")
    val tempDir = rootDir.resolve(DefaultEvalPaths.DefaultTempDir)
    DefaultExecPaths(rootDir, tempDir)
  }

  def createContainerPaths(containerExecutionDir: Path,
                           containerTempDir: Path = Paths.get("/tmp")): ExecPaths = {
    DefaultExecPaths(containerExecutionDir, containerTempDir)
  }

  def createLocalContainerPair(
      useWorkingDir: Boolean = false,
      containerMountDir: Path,
      containerTempDir: Path = Paths.get("/tmp")
  ): (ExecPaths, ExecPaths) = {
    val localPaths = if (useWorkingDir) {
      createLocalPathsFromDir()
    } else {
      createLocalPathsFromTemp()
    }
    val containerPaths = createContainerPaths(containerMountDir, containerTempDir)
    (localPaths, containerPaths)
  }
}
