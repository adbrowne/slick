import sbt._
import Keys._
import Tests._

object SLICKBuild extends Build {

  /////////////////////////////////////////////////////////////// Main project

  /* Custom Settings */
  val useJDBC4 = SettingKey[Boolean]("use-jdbc4", "Use JDBC 4 (Java 1.6+) or JDBC 3 (Java 1.5)")
  val repoKind = SettingKey[String]("repo-kind", "Maven repository kind (\"snapshots\" or \"releases\")")

  /* Project Definition */
  lazy val mainProject = Project(id = "slick", base = file("."),
    settings = Project.defaultSettings ++ fmppSettings ++ Seq(
      useJDBC4 := (
        try { classOf[java.sql.DatabaseMetaData].getMethod("getClientInfoProperties"); true }
        catch { case _:NoSuchMethodException => false } ),
      repoKind <<= (version)(v => if(v.trim.endsWith("SNAPSHOT")) "snapshots" else "releases"),
      scalacOptions in doc <++= (version).map(v => Seq("-doc-title", "SLICK", "-doc-version", v)),
      makePomConfiguration ~= { _.copy(configurations = Some(Seq(Compile, Runtime))) }
  ))

  /* Split tests into a group that needs to be forked and another one that can run in-process */
  def partitionTests(tests: Seq[TestDefinition]) = {
    val (fork, notFork) = tests partition (_.name contains ".queryable.")
    Seq(
      new Group("fork", fork, SubProcess(Seq())),
      new Group("inProcess", notFork, InProcess)
    )
  }

  /* FMPP Task */
  lazy val fmpp = TaskKey[Seq[File]]("fmpp")
  lazy val fmppConfig = config("fmpp") hide
  lazy val fmppSettings = inConfig(Compile)(Seq(sourceGenerators <+= fmpp, fmpp <<= fmppTask)) ++ Seq(
    libraryDependencies += "net.sourceforge.fmpp" % "fmpp" % "0.9.14" % fmppConfig.name,
    ivyConfigurations += fmppConfig,
    fullClasspath in fmppConfig <<= update map { _ select configurationFilter(fmppConfig.name) map Attributed.blank },
    //mappings in (Compile, packageSrc) <++= // Add generated sources to sources JAR
    //  (sourceManaged in Compile, managedSources in Compile) map { (b, s) => s x (Path.relativeTo(b) | Path.flat) }
    mappings in (Compile, packageSrc) <++=
      (sourceManaged in Compile, managedSources in Compile, sourceDirectory in Compile) map { (base, srcs, srcDir) =>
        val fmppSrc = srcDir / "scala"
        val inFiles = fmppSrc ** "*.fm"
        (srcs x (Path.relativeTo(base) | Path.flat)) ++ // Add generated sources to sources JAR
          (inFiles x (Path.relativeTo(fmppSrc) | Path.flat)) // Add *.fm files to sources JAR
      }
  )
  lazy val fmppTask =
    (fullClasspath in fmppConfig, runner in fmpp, sourceManaged, streams, cacheDirectory, sourceDirectory) map { (cp, r, output, s, cache, srcDir) =>
      val fmppSrc = srcDir / "scala"
      val inFiles = (fmppSrc ** "*.fm" get).toSet
      val cachedFun = FileFunction.cached(cache / "fmpp", outStyle = FilesInfo.exists) { (in: Set[File]) =>
        IO.delete(output ** "*.scala" get)
        val args = "--expert" :: "-q" :: "-S" :: fmppSrc.getPath :: "-O" :: output.getPath ::
          "--replace-extensions=fm, scala" :: "-M" :: "execute(**/*.fm), ignore(**/*)" :: Nil
        toError(r.run("fmpp.tools.CommandLine", cp.files, args, s.log))
        (output ** "*.scala").get.toSet
      }
      cachedFun(inFiles).toSeq
    }

  /////////////////////////////////////////////////////////////// Docs project

  /* Project Definition */
  lazy val docsProject = Project(id = "slick-docs", base = file("slick-docs"),
    settings = Project.defaultSettings ++ rstSettings)

  /* RST Task */
  lazy val rst = TaskKey[Seq[File]]("rst")
  lazy val rstConfig = config("rst") hide
  lazy val rstSettings = inConfig(Compile)(Seq(sourceGenerators <+= rst, rst <<= rstTask)) ++ Seq(
    libraryDependencies += "org.nuiton.jrst" % "jrst" % "1.5" % rstConfig.name,
    ivyConfigurations += rstConfig,
    fullClasspath in rstConfig <<= update map { _ select configurationFilter(rstConfig.name) map Attributed.blank }
  )
  lazy val rstTask =
    (fullClasspath in rstConfig, runner in rst, target, streams, cacheDirectory, sourceDirectory) map { (cp, r, target, s, cache, srcDir) =>
      val rstSrc = srcDir / "rst"
      val output = target / "rst"
      output.mkdirs
      val inFiles = (rstSrc ** "*.rst" get).toSet
      IO.delete(output ** "*.html" get)
      IO.delete(output ** "*.pdf" get)
      inFiles.foreach { inFile =>
        val args1 = "--force" :: "-t" :: "html" :: "-o" :: (output / inFile.getName.replaceAll("\\.rst$", ".html")).getPath :: inFile.getPath :: Nil
        val args2 = "--force" :: "-t" :: "pdf" :: "-o" :: (output / inFile.getName.replaceAll("\\.rst$", ".pdf")).getPath :: inFile.getPath :: Nil
        toError(r.run("org.nuiton.jrst.JRST", cp.files, args1, s.log))
        toError(r.run("org.nuiton.jrst.JRST", cp.files, args2, s.log))
      }
      ((output ** "*.html").get ++ (output ** "*.pdf").get).toSeq
    }
}
