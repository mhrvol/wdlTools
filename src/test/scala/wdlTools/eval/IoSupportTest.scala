package wdlTools.eval

import java.nio.file.{Files, Paths}

import org.scalatest.Inside
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import wdlTools.syntax.{SourceLocation, WdlVersion}
import wdlTools.util.{
  FileAccessProtocol,
  FileSource,
  FileSourceResolver,
  FileUtils,
  Logger,
  StringFileSource
}

class IoSupportTest extends AnyFlatSpec with Matchers with Inside {
  private val srcDir = Paths.get(getClass.getResource("/eval").getPath)
  private val logger = Logger.Normal

  case object DxProtocol extends FileAccessProtocol {
    val prefixes = Vector("dx")
    override def resolve(uri: String): FileSource = ???
  }

  private def setup(): (EvalPaths, FileSourceResolver) = {
    val baseDir = Files.createTempDirectory("eval")
    baseDir.toFile.deleteOnExit()
    val tmpDir = baseDir.resolve("tmp")
    val evalPaths = EvalPaths(baseDir, tmpDir)
    val fileResolver =
      FileSourceResolver.create(Vector(srcDir, evalPaths.getHomeDir()), Vector(DxProtocol))
    (evalPaths, fileResolver)
  }

  private val placeholderSourceLocation = SourceLocation.empty

  it should "be able to get size of a local file" in {
    val p = Files.createTempFile("Y", ".txt")
    try {
      val buf = "hello bunny"
      val docSrc = StringFileSource(buf, Some(p))
      docSrc.localize(overwrite = true)
      val (evalPaths, fileResolver) = setup()
      val ioSupp = IoSupport(evalPaths, fileResolver, logger)
      val len = ioSupp.size(p.toString, placeholderSourceLocation)
      len shouldBe buf.length
      val data = ioSupp.readFile(p.toString, placeholderSourceLocation)
      data shouldBe buf
    } finally {
      Files.delete(p)
    }
  }

  it should "be able to use size from Stdlib" in {
    val p = Files.createTempFile("Y", ".txt")
    val buf = "make Shasta full"
    try {
      val docSrc = StringFileSource(buf, Some(p))
      docSrc.localize(overwrite = true)
      val (evalPaths, fileResolver) = setup()
      val stdlib = Stdlib(evalPaths, WdlVersion.V1, fileResolver, logger)
      val retval =
        stdlib.call("size", Vector(WdlValues.V_String(p.toString)), placeholderSourceLocation)
      inside(retval) {
        case WdlValues.V_Float(x) =>
          x.toInt shouldBe buf.length
      }
    } finally {
      Files.delete(p)
    }
  }

  it should "evaluate globs" in {
    val (evalPaths, fileResolver) = setup()
    val homeDir = evalPaths.getHomeDir(ensureExists = true)
    val file1 = homeDir.resolve("file1.txt")
    val file2 = homeDir.resolve("file2.txt")
    val files = Set(file1, file2)
    files.foreach { path =>
      FileUtils.writeFileContent(path, "foo")
    }
    val ioSupp = IoSupport(evalPaths, fileResolver, logger)
    val globFiles = ioSupp.glob("./*.txt")
    globFiles.toSet shouldBe files.map(_.toString)
  }
}
