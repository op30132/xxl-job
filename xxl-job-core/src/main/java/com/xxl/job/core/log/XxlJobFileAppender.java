package com.xxl.job.core.log;

import com.xxl.job.core.openapi.model.LogResult;
import com.xxl.tool.core.DateTool;
import com.xxl.tool.core.StringTool;
import com.xxl.tool.io.FileTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * store trigger log in each log-file
 *
 * @author xuxueli 2016-3-12 19:25:12
 */
public class XxlJobFileAppender {
	private static final Logger logger = LoggerFactory.getLogger(XxlJobFileAppender.class);

	/**
	 * log base path
	 *
	 * strut like:
	 * 	---/
	 * 	---/gluesource/10_1514171108000.js
	 * 	---/callbacklogs/xxl-job-callback-1761412677119.log
	 * 	---/2017-12-25/639.log
	 * 	---/2017-12-25/821.log
	 *
	 */
	private static String logBasePath = "/data/applogs/xxl-job/jobhandler";
	private static String glueSrcPath = logBasePath.concat(File.separator).concat("gluesource");
	private static String callbackLogPath = logBasePath.concat(File.separator).concat("callbacklogs");
	public static void initLogPath(String logPath) throws IOException {
		// init
		if (StringTool.isNotBlank(logPath)) {
			logBasePath = logPath.trim();
		}
		// mk base dir
		File logPathDir = new File(logBasePath);
        FileTool.createDirectories(logPathDir);
		logBasePath = logPathDir.getCanonicalPath();

		// mk glue dir
		File glueBaseDir = new File(logPathDir, "gluesource");
        FileTool.createDirectories(glueBaseDir);
		glueSrcPath = glueBaseDir.getCanonicalPath();

		// mk callback dir
		File callbackBaseDir = new File(logPathDir, "callbacklogs");
        FileTool.createDirectories(callbackBaseDir);
		callbackLogPath = callbackBaseDir.getCanonicalPath();
	}
	public static String getLogPath() {
		return logBasePath;
	}
	public static String getGlueSrcPath() {
		return glueSrcPath;
	}
	public static String getCallbackLogPath() {
		return callbackLogPath;
	}

	/**
	 * log filename, like "logPath/yyyy-MM-dd/9999.log"
	 *
	 * @param logId log id
	 * @return      log file name
	 */
	public static String makeLogFileName(Date triggerDate, long logId) {

		// "filePath/yyyy-MM-dd"
		File logFilePath = new File(getLogPath(), DateTool.formatDate(triggerDate));
        try {
            FileTool.createDirectories(logFilePath);
        } catch (IOException e) {
            throw new RuntimeException("XxlJobFileAppender makeLogFileName error, logFilePath:"+ logFilePath.getPath(), e);
        }

        // filePath/yyyy-MM-dd/9999.log
        return logFilePath.getPath()
                .concat(File.separator)
                .concat(String.valueOf(logId))
                .concat(".log");
	}

    public static String resolveLogFilePath(String logFileName) throws IOException {
        return resolvePathUnderBase(logBasePath, logFileName, "log file");
    }

    public static String resolveGlueSourceFilePath(String scriptFileName) throws IOException {
        return resolvePathUnderBase(glueSrcPath, scriptFileName, "glue source file");
    }

    private static String resolvePathUnderBase(String baseDirectory, String candidatePath, String pathType) throws IOException {
        if (StringTool.isBlank(candidatePath)) {
            throw new IOException(pathType + " path is blank.");
        }

        Path basePath = Paths.get(baseDirectory).toAbsolutePath().normalize();
        Path resolvedPath = Paths.get(candidatePath).toAbsolutePath().normalize();
        if (!resolvedPath.startsWith(basePath)) {
            throw new IOException("Invalid " + pathType + " path: " + candidatePath);
        }
        return resolvedPath.toString();
    }

	/**
	 * append log
	 *
	 * @param logFileName	log file name
	 * @param appendLog		append log
	 */
	public static void appendLog(String logFileName, String appendLog) {

		// valid
		if (StringTool.isBlank(logFileName) || appendLog == null) {
			return;
		}

		// append log
        try {
            String safeLogFileName = resolveLogFilePath(logFileName);
            FileTool.writeLines(safeLogFileName, List.of(appendLog), true);
        } catch (IOException e) {
            throw new RuntimeException("XxlJobFileAppender appendLog error, logFileName:"+ logFileName, e);
        }
	}

	/**
	 * support read log-file
	 *
	 * @param logFileName	log file name
	 * @param fromLineNum	from line num
	 * @return log content
	 */
	public static LogResult readLog(String logFileName, final int fromLineNum){

		// valid
		if (StringTool.isBlank(logFileName)) {
            return new LogResult(fromLineNum, 0, "readLog fail, logFile not found", true);
		}
        final String safeLogFileName;
        try {
            safeLogFileName = resolveLogFilePath(logFileName);
        } catch (IOException e) {
            logger.warn("XxlJobFileAppender readLog rejected invalid path, logFileName:{}", logFileName, e);
            return new LogResult(fromLineNum, 0, "readLog fail, invalid logFileName", true);
        }
		if (!FileTool.exists(safeLogFileName)) {
            return new LogResult(fromLineNum, 0, "readLog fail, logFile not exists", true);
		}

		// read data
        StringBuilder logContentBuilder = new StringBuilder();
        // num: [from, to], start as 1
        AtomicInteger toLineNum = new AtomicInteger(0);
        AtomicInteger currentLineNum = new AtomicInteger(0);
        /*int readLineCount = 0;*/

        // do read
        try {
            FileTool.readLines(safeLogFileName, new Consumer<String>() {
                @Override
                public void accept(String line) {
                    // refresh line num
                    currentLineNum.incrementAndGet();

                    // valid
                    if (currentLineNum.get() < fromLineNum) {
                        return;
                    }

                    // Limit return less than 1000 rows per query request	// todo
                    /*if(++readLineCount >= 1000) {
                        break;
                    }*/

                    // collect line data
                    toLineNum.set(currentLineNum.get());
                    logContentBuilder.append(line).append(System.lineSeparator());      // [from, to], start as 1
                }
            });
        } catch (IOException e) {
            logger.error("XxlJobFileAppender readLog error, logFileName:{}, fromLineNum:{}", safeLogFileName, fromLineNum, e);
        }

        // result
        return new LogResult(fromLineNum, toLineNum.get(), logContentBuilder.toString(), false);
	}

}
