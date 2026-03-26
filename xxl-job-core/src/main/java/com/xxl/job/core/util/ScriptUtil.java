package com.xxl.job.core.util;

import com.xxl.job.core.context.XxlJobHelper;
	import com.xxl.job.core.log.XxlJobFileAppender;
	import com.xxl.tool.core.ArrayTool;
	import com.xxl.tool.io.FileTool;
	import com.xxl.tool.io.IOTool;

	import java.io.FileOutputStream;
	import java.io.IOException;
	import java.util.ArrayList;
	import java.util.Date;
	import java.util.List;

/**
 *  1、内嵌编译器如"PythonInterpreter"无法引用扩展包，因此推荐使用java调用控制台进程方式"Runtime.getRuntime().exec()"来运行脚本(shell或python)；
 *  2、因为通过java调用控制台进程方式实现，需要保证目标机器PATH路径正确配置对应编译器；
 *  3、暂时脚本执行日志只能在脚本执行结束后一次性获取，无法保证实时性；因此为确保日志实时性，可改为将脚本打印的日志存储在指定的日志文件上；
 *  4、python 异常输出优先级高于标准输出，体现在Log文件中，因此推荐通过logging方式打日志保持和异常信息一致；否则用prinf日志顺序会错乱
 *
 * Created by xuxueli on 17/2/25.
 */
public class ScriptUtil {

    /**
     * make script file
     *
     * @param scriptFileName        script file name
     * @param scriptContent         script content
     * @throws IOException exception
     */
    public static void markScriptFile(String scriptFileName, String scriptContent) throws IOException {
        // make file: filePath/gluesource/666-123456789.py
        FileTool.writeString(scriptFileName, scriptContent);

        /*FileOutputStream fileOutputStream = null;
        try {
            fileOutputStream = new FileOutputStream(scriptFileName);
            fileOutputStream.write(scriptContent.getBytes("UTF-8"));
            fileOutputStream.close();
        } catch (Exception e) {
            throw e;
        }finally{
            if(fileOutputStream != null){
                fileOutputStream.close();
            }
        }*/
    }

    /**
     * 脚本执行，日志文件实时输出 (whitelist-safe version)
     * Uses controlled path construction to prevent path traversal attacks
     *
     * @param command       command
     * @param scriptFile    script file
     * @param triggerDate   trigger date for log path construction
     * @param logId         log id for log file name
     * @param params        params
     * @return  exit code
     * @throws IOException exception
     */
    public static int execToFile(String command, String scriptFile, Date triggerDate, long logId, String... params) throws IOException {

        if (triggerDate == null) {
            XxlJobHelper.log("ScriptUtil execToFile error: triggerDate cannot be null");
            return -1;
        }

        // Path is safely constructed using only the logId (numeric) and triggerDate
        String logFile = XxlJobFileAppender.makeLogFileName(triggerDate, logId);

        FileOutputStream fileOutputStream = null;
        Thread inputThread = null;
        Thread errorThread = null;
        Process process = null;
        try {
            // 1、build file OutputStream
            fileOutputStream = new FileOutputStream(logFile, true);

            // 2、build command
            List<String> cmdarray = new ArrayList<>();
            cmdarray.add(command);
            cmdarray.add(scriptFile);
            if (ArrayTool.isNotEmpty(params)) {
                for (String param:params) {
                    cmdarray.add(param);
                }
            }
            String[] cmdarrayFinal = cmdarray.toArray(new String[0]);

            // 3、process：exec
            process = Runtime.getRuntime().exec(cmdarrayFinal);
            Process finalProcess = process;

            // 4、read script log: inputStream + errStream
            final FileOutputStream finalFileOutputStream = fileOutputStream;
            inputThread = new Thread(() -> {
                try {
                    // 数据流Copy（Input自动关闭，Output不处理）
                    IOTool.copy(finalProcess.getInputStream(), finalFileOutputStream, true, false);
                } catch (IOException e) {
                    XxlJobHelper.log(e);
                }
            });
            errorThread = new Thread(() -> {
                try {
                    IOTool.copy(finalProcess.getErrorStream(), finalFileOutputStream, true, false);
                } catch (IOException e) {
                    XxlJobHelper.log(e);
                }
            });
            inputThread.start();
            errorThread.start();

            // 5、process：wait for result
            int exitValue = process.waitFor();      // exit code: 0=success, 1=error

            // 6、thread join, wait for log
            inputThread.join();
            errorThread.join();

            return exitValue;
        } catch (Exception e) {
            XxlJobHelper.log(e);
            return -1;
        } finally {
            // 7、close file OutputStream
            if (fileOutputStream != null) {
                try {
                    fileOutputStream.close();
                } catch (IOException e) {
                    XxlJobHelper.log(e);
                }
            }
            // 8、interrupt thread
            if (inputThread != null && inputThread.isAlive()) {
                inputThread.interrupt();
            }
            if (errorThread != null && errorThread.isAlive()) {
                errorThread.interrupt();
            }
            // 9、process destroy
            if (process != null) {
                process.destroy();
                // process.destroyForcibly();
            }
        }
    }

    /**
     * 脚本执行，日志文件实时输出 (legacy version - DEPRECATED, use execToFile(String, String, Date, long, String...))
     * Kept for backward compatibility only
     *
     * @param command       command
     * @param scriptFile    script file
     * @param logFile       log file
     * @param params        params
     * @return  exit code
     * @throws IOException exception
     * @deprecated Use {@link #execToFile(String, String, Date, long, String...)} instead
     */
    @Deprecated
    public static int execToFile(String command, String scriptFile, String logFile, String... params) throws IOException {

        // path traversal validation - resolve and verify path is within base directory
        String sanitizedLogFile;
        try {
            sanitizedLogFile = resolveAndValidatePath(logFile);
        } catch (IOException e) {
            XxlJobHelper.log("ScriptUtil execToFile error, invalid log file path: " + e.getMessage());
            throw new IOException("Invalid log file path", e);
        }

        FileOutputStream fileOutputStream = null;
        Thread inputThread = null;
        Thread errorThread = null;
        Process process = null;
        try {
            // 1、build file OutputStream
            fileOutputStream = new FileOutputStream(sanitizedLogFile, true);

            // 2、build command
            List<String> cmdarray = new ArrayList<>();
            cmdarray.add(command);
            cmdarray.add(scriptFile);
            if (ArrayTool.isNotEmpty(params)) {
                for (String param:params) {
                    cmdarray.add(param);
                }
            }
            String[] cmdarrayFinal = cmdarray.toArray(new String[0]);

            // 3、process：exec
            process = Runtime.getRuntime().exec(cmdarrayFinal);
            Process finalProcess = process;

            // 4、read script log: inputStream + errStream
            final FileOutputStream finalFileOutputStream = fileOutputStream;
            inputThread = new Thread(() -> {
                try {
                    // 数据流Copy（Input自动关闭，Output不处理）
                    IOTool.copy(finalProcess.getInputStream(), finalFileOutputStream, true, false);
                } catch (IOException e) {
                    XxlJobHelper.log(e);
                }
            });
            errorThread = new Thread(() -> {
                try {
                    IOTool.copy(finalProcess.getErrorStream(), finalFileOutputStream, true, false);
                } catch (IOException e) {
                    XxlJobHelper.log(e);
                }
            });
            inputThread.start();
            errorThread.start();

            // 5、process：wait for result
            int exitValue = process.waitFor();      // exit code: 0=success, 1=error

            // 6、thread join, wait for log
            inputThread.join();
            errorThread.join();

            return exitValue;
        } catch (Exception e) {
            XxlJobHelper.log(e);
            return -1;
        } finally {
            // 7、close file OutputStream
            if (fileOutputStream != null) {
                try {
                    fileOutputStream.close();
                } catch (IOException e) {
                    XxlJobHelper.log(e);
                }
            }
            // 8、interrupt thread
            if (inputThread != null && inputThread.isAlive()) {
                inputThread.interrupt();
            }
            if (errorThread != null && errorThread.isAlive()) {
                errorThread.interrupt();
            }
            // 9、process destroy
            if (process != null) {
                process.destroy();
                // process.destroyForcibly();
            }
        }
    }

    /**
     * 脚本执行，日志文件实时输出
     *
     * 优点：支持将目标数据实时输出到指定日志文件中去
     * 缺点：
     *      标准输出和错误输出优先级固定，可能和脚本中顺序不一致
     *      Java无法实时获取
     *
     *      <!-- commons-exec -->
     * 		<dependency>
     * 			<groupId>org.apache.commons</groupId>
     * 			<artifactId>commons-exec</artifactId>
     * 			<version>${commons-exec.version}</version>
     * 		</dependency>
     *
     * @param command
     * @param scriptFile
     * @param logFile
     * @param params
     * @return
     * @throws IOException
     */
    /*public static int execToFileB(String command, String scriptFile, String logFile, String... params) throws IOException {
        // 标准输出：print （null if watchdog timeout）
        // 错误输出：logging + 异常 （still exists if watchdog timeout）
        // 标准输入

        FileOutputStream fileOutputStream = null;   //
        try {
            fileOutputStream = new FileOutputStream(logFile, true);
            PumpStreamHandler streamHandler = new PumpStreamHandler(fileOutputStream, fileOutputStream, null);

            // command
            CommandLine commandline = new CommandLine(command);
            commandline.addArgument(scriptFile);
            if (params!=null && params.length>0) {
                commandline.addArguments(params);
            }

            // exec
            DefaultExecutor exec = new DefaultExecutor();
            exec.setExitValues(null);
            exec.setStreamHandler(streamHandler);
            int exitValue = exec.execute(commandline);  // exit code: 0=success, 1=error
            return exitValue;
        } catch (Exception e) {
            XxlJobLogger.log(e);
            return -1;
        } finally {
            if (fileOutputStream != null) {
                try {
                    fileOutputStream.close();
                } catch (IOException e) {
                    XxlJobLogger.log(e);
                }

            }
        }
    }*/

    /**
     * Resolve and validate that the given file path is within the allowed log base directory.
     * Returns the canonical path after validation to prevent path traversal attacks.
     *
     * @param filePath file path to validate
     * @return canonical path if valid
     * @throws IOException if path cannot be resolved or is outside base directory
     */
    private static String resolveAndValidatePath(String filePath) throws IOException {
        if (filePath == null || filePath.trim().isEmpty()) {
            throw new IOException("File path cannot be blank");
        }

        java.io.File file = new java.io.File(filePath);
        java.io.File baseDir = new java.io.File(XxlJobFileAppender.getLogPath());

        // Get canonical paths to resolve symlinks and relative paths like ..
        String canonicalFilePath = file.getCanonicalPath();
        String canonicalBaseDir = baseDir.getCanonicalPath();

        // Check if the file path starts with the base directory path
        if (!canonicalFilePath.startsWith(canonicalBaseDir)) {
            throw new IOException("File path " + filePath + " is outside of allowed base directory");
        }

        return canonicalFilePath;
    }

}
