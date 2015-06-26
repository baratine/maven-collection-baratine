package com.caucho.maven;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Mojo(name = "run", defaultPhase = LifecyclePhase.NONE, requiresProject = true,
      threadSafe = true, requiresDependencyResolution = ResolutionScope.RUNTIME)
public class RunMojo extends BaratineExecutableMojo
{
  @Parameter(defaultValue = "${session}", readonly = true, required = true)
  private MavenSession session;

  @Parameter
  private String script;

  @Parameter(defaultValue = "8085", property = "baratine.port")
  private int port;

  @Parameter(defaultValue = "${java.io.tmpdir}/baratine",
             property = "baratine.workDir")
  private String workDir;

  @Parameter(property = "baratine.run.skip")
  private boolean runSkip = false;

  @Parameter(property = "baratine.run.verbose")
  private boolean verbose = false;

  public void execute() throws MojoExecutionException, MojoFailureException
  {
    if (runSkip)
      return;

    String cp = getBaratine();
    cp = cp + File.pathSeparatorChar;
    cp = cp + getBaratineApi();

    String javaHome = System.getProperty("java.home");

    List<String> command = new ArrayList<>();
    command.add(javaHome + "/bin/java");
    command.add("-cp");
    command.add(cp);
    command.add("com.caucho.cli.baratine.BaratineCommandLine");

    ExecutorService x = Executors.newFixedThreadPool(3);
    Process process = null;

    try {
      ProcessBuilder processBuilder = new ProcessBuilder(command);
      process = processBuilder.start();

      InputStream in = process.getInputStream();
      InputStream err = process.getErrorStream();
      OutputStream out = process.getOutputStream();

      x.submit(new StreamPiper(in, System.out));
      x.submit(new StreamPiper(err, System.err));
      x.submit(new StreamPiper(System.in, out));

      String cmd = String.format("start -bg --root-dir %1$s -p %2$d %3$s\n",
                                 this.workDir,
                                 this.port,
                                 (verbose?"-vv":""));

      out.write(cmd.getBytes());
      out.flush();
      Thread.sleep(2 * 1000);

      cmd = String.format("deploy %1$s\n", getBarLocation());

      out.write(cmd.getBytes());
      out.flush();
      Thread.sleep(2 * 1000);

      if (script != null) {
        byte[] buffer = script.getBytes(StandardCharsets.UTF_8);

        int i = 0;

        for (; i < buffer.length && buffer[i] == ' '; i++) ;

        int start = i;

        getLog().info("running Baratine Script");

        for (; i < buffer.length; i++) {
          if (buffer[i] == '\n' || i == buffer.length - 1) {
            int len = i - start;
            if (i == buffer.length - 1)
              len += 1;

            String scriptCmd = new String(buffer, start, len);
            getLog().info("baratine>" + scriptCmd);

            out.write((scriptCmd + '\n').getBytes());
            out.flush();
            Thread.sleep(400);

            for (;
                 i < buffer.length && (buffer[i] == ' ' || buffer[i] == '\n');
                 i++)
              ;

            start = i;
          }
        }
      }

      getLog().info("Baratine terminated: " + process.waitFor());
    } catch (Exception e) {
      String message = String.format("exception running baratine %1$s",
                                     e.getMessage());
      throw new MojoExecutionException(message, e);
    } finally {
      try {
        x.shutdown();
        x.awaitTermination(1, TimeUnit.SECONDS);
      } catch (Throwable t) {
      } finally {
        x.shutdownNow();
      }

      try {
        if (process != null)
          process.waitFor(2, TimeUnit.SECONDS);
      } catch (Throwable t) {
      } finally {
        if (process.isAlive())
          process.destroyForcibly();
      }
    }
  }

  static class StreamPiper implements Runnable
  {
    InputStream _in;
    OutputStream _out;

    public StreamPiper(InputStream in, OutputStream out)
    {
      _in = in;
      _out = out;
    }

    @Override
    public void run()
    {
      byte[] buffer = new byte[256];

      int i;

      try {
        while ((i = _in.read(buffer)) > 0) {
          _out.write(buffer, 0, i);
          _out.flush();
        }
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }
}
