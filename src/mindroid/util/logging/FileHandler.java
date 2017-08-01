/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package mindroid.util.logging;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import mindroid.content.SharedPreferences;
import mindroid.os.Environment;
import mindroid.util.logging.LogBuffer.LogRecord;

/**
 * A {@code FileHandler} writes logging records into a specified file or a rotating set of files.
 * <p>
 * When a set of files is used and a given amount of data has been written to one file, then this
 * file is closed and another file is opened. The name of these files are generated by given name
 * pattern, see below for details. When the files have all been filled the Handler returns to the
 * first and goes through the set again.
 * <p>
 * By default, the I/O buffering mechanism is enabled, but when each log record is complete, it is
 * flushed out.
 * <p>
 * Name pattern is a string that may include some special substrings, which will be replaced to
 * generate output files:
 * <ul>
 * <li>"/" represents the local pathname separator</li>
 * <li>"%g" represents the generation number to distinguish rotated logs</li>
 * <li>"%h" represents the home directory of the current user, which is specified by "user.home"
 * system property</li>
 * <li>"%t" represents the system's temporary directory</li>
 * <li>"%%" represents the percent sign character '%'</li>
 * </ul>
 * <p>
 * Normally, the generation numbers are not larger than the given file count and follow the sequence
 * 0, 1, 2.... If the file count is larger than one, but the generation field("%g") has not been
 * specified in the pattern, then the generation number after a dot will be added to the end of the
 * file name.
 */
public class FileHandler extends Handler {
    private static final int DEFAULT_COUNT = 1;
    private static final int DEFAULT_LIMIT = 0;
    private static final boolean DEFAULT_APPEND = false;
    private static final String DEFAULT_PATTERN = "%h/Mindroid-%g.log";
    private static final String CRLF = "\r\n";
    private static final String DATA_VOLUME = "dataVolume";

    private String mPattern;
    private boolean mAppend;
    private int mLimit;
    private int mCount;
    private Writer mWriter;
    private File[] mFiles;
    private int mBufferSize = 0;
    private int mFlushSize = 0;
    private int mDataVolume = 0;
    private int mDataVolumeLimit = 0;
    private SharedPreferences mPreferences;

    public FileHandler() throws IOException {
        init(null, null, null, null, null, null);
    }

    private void init(String pattern, Boolean append, Integer limit, Integer count, Integer bufferSize, Integer dataVolume) throws IOException {
        initProperties(pattern, append, limit, count, bufferSize, dataVolume);
        initOutputFiles();
        initPreferences();
    }

    private void initProperties(String p, Boolean a, Integer l, Integer c, Integer bufferSize, Integer dataVolumeLimit) {
        mPattern = (p == null) ? DEFAULT_PATTERN : p;
        if (mPattern == null || (mPattern.length() == 0)) {
            throw new NullPointerException("Pattern cannot be null or empty");
        }
        mAppend = (a == null) ? DEFAULT_APPEND : a.booleanValue();
        mCount = (c == null) ? DEFAULT_COUNT : c.intValue();
        mLimit = (l == null) ? DEFAULT_LIMIT : l.intValue();
        mBufferSize = (bufferSize == null) ? 0 : bufferSize.intValue();
        mDataVolumeLimit = (dataVolumeLimit == null) ? 0 : dataVolumeLimit.intValue();
        mCount = mCount < 1 ? DEFAULT_COUNT : mCount;
        mLimit = mLimit < 0 ? DEFAULT_LIMIT : mLimit;
        mFiles = new File[mCount];
    }
    
    private void initOutputFiles() throws FileNotFoundException, IOException {
        for (int generation = 0; generation < mCount; generation++) {
            mFiles[generation] = new File(parseFileName(generation));
        }
        if (mFiles[0].exists() && (!mAppend || mFiles[0].length() >= mLimit)) {
            for (int i = mCount - 1; i > 0; i--) {
                if (mFiles[i].exists()) {
                    mFiles[i].delete();
                }
                mFiles[i - 1].renameTo(mFiles[i]);
            }
        }
        mWriter = new Writer(mFiles[0], mAppend);
    }
    
    private void initPreferences() {
        if (mDataVolumeLimit > 0) {
            String fileName = Integer.toHexString(mFiles[0].getAbsolutePath().hashCode()) + ".xml";
            mPreferences = Environment.getSharedPreferences(mFiles[0].getParentFile(), fileName, 0);
            mDataVolume = mPreferences.getInt(DATA_VOLUME, 0);
        }
    }

    void findNextGeneration() {
        close();

        for (int i = mCount - 1; i > 0; i--) {
            if (mFiles[i].exists()) {
                mFiles[i].delete();
            }
            mFiles[i - 1].renameTo(mFiles[i]);
        }
        try {
            mWriter = new Writer(mFiles[0], mAppend);
        } catch (IOException e) {
            System.out.println("Error opening log file");
        }
    }

    /**
     * Transform the pattern to the valid file name, replacing any patterns, and applying generation
     * if present.
     * 
     * @param gen Generation of this file.
     * @return Transformed filename ready for use.
     */
    private String parseFileName(int gen) {
        int curChar = 0;
        int nextChar = 0;
        boolean hasGeneration = false;

        String tempPath = System.getProperty("java.io.tmpdir");
        boolean tempPathHasSeparatorEnd = (tempPath == null ? false : tempPath.endsWith(File.separator));

        String homePath = System.getProperty("user.home");
        boolean homePathHasSeparatorEnd = (homePath == null ? false : homePath.endsWith(File.separator));

        StringBuilder sb = new StringBuilder();
        mPattern = mPattern.replace('/', File.separatorChar);

        char[] value = mPattern.toCharArray();
        while ((nextChar = mPattern.indexOf('%', curChar)) >= 0) {
            if (++nextChar < mPattern.length()) {
                switch (value[nextChar]) {
                case 'g':
                    sb.append(value, curChar, nextChar - curChar - 1).append(gen);
                    hasGeneration = true;
                    break;
                case 't':
                    sb.append(value, curChar, nextChar - curChar - 1).append(tempPath);
                    if (!tempPathHasSeparatorEnd) {
                        sb.append(File.separator);
                    }
                    if (nextChar + 1 < mPattern.length()) {
                        if (value[nextChar + 1] == File.separatorChar) {
                            ++nextChar;
                        }
                    }
                    break;
                case 'h':
                    sb.append(value, curChar, nextChar - curChar - 1).append(homePath);
                    if (!homePathHasSeparatorEnd) {
                        sb.append(File.separator);
                    }
                    if (nextChar + 1 < mPattern.length()) {
                        if (value[nextChar + 1] == File.separatorChar) {
                            ++nextChar;
                        }
                    }
                    break;
                case '%':
                    sb.append(value, curChar, nextChar - curChar - 1).append('%');
                    break;
                default:
                    sb.append(value, curChar, nextChar - curChar);
                }
                curChar = ++nextChar;
            }
        }

        sb.append(value, curChar, value.length - curChar);

        if (!hasGeneration && mCount > 1) {
            sb.append(".").append(gen);
        }

        return sb.toString();
    }

    /**
     * Constructs a new {@code FileHandler}. The given name pattern is used as output filename, the
     * file limit is set to zero (no limit), the file count is set to one. This handler writes to
     * only one file with no size limit.
     * 
     * @param pattern The name pattern for the output file.
     * @throws IOException if any I/O error occurs.
     * @throws IllegalArgumentException if the pattern is empty.
     * @throws NullPointerException if the pattern is {@code null}.
     */
    public FileHandler(String pattern) throws IOException {
        if (pattern == null || pattern.length() == 0) {
            throw new IllegalArgumentException("Pattern cannot be null or empty");
        }
        init(pattern, null, new Integer(DEFAULT_LIMIT), new Integer(DEFAULT_COUNT), null, null);
    }

    /**
     * Construct a new {@code FileHandler}. The given name pattern is used as output filename, the
     * file limit is set to zero (no limit), the file count is initialized to one and the value of
     * {@code append} becomes the new instance's append mode. This handler writes to only one file
     * with no size limit.
     * 
     * @param pattern The name pattern for the output file.
     * @param append The append mode.
     * @throws IOException if any I/O error occurs.
     * @throws IllegalArgumentException if {@code pattern} is empty.
     * @throws NullPointerException if {@code pattern} is {@code null}.
     */
    public FileHandler(String pattern, boolean append) throws IOException {
        if (pattern == null || pattern.length() == 0) {
            throw new IllegalArgumentException("Pattern cannot be null or empty");
        }
        init(pattern, Boolean.valueOf(append), new Integer(DEFAULT_LIMIT), new Integer(DEFAULT_COUNT), null, null);
    }

    /**
     * Construct a new {@code FileHandler}. The given name pattern is used as output filename, the
     * maximum file size is set to {@code limit} and the file count is initialized to {@code count}.
     * This handler is configured to write to a rotating set of count files, when the limit of bytes
     * has been written to one output file, another file will be opened instead.
     * 
     * @param pattern The name pattern for the output file.
     * @param limit The data amount limit in bytes of one output file, can not be negative.
     * @param count The maximum number of files to use, can not be less than one.
     * @throws IOException if any I/O error occurs.
     * @throws IllegalArgumentException if {@code pattern} is empty, {@code limit < 0} or
     * {@code count < 1}.
     * @throws NullPointerException if {@code pattern} is {@code null}.
     */
    public FileHandler(String pattern, int limit, int count) throws IOException {
        if (pattern == null || pattern.length() == 0) {
            throw new IllegalArgumentException("Pattern cannot be null or empty");
        }
        if (limit < 0 || count < 1) {
            throw new IllegalArgumentException("limit < 0 || count < 1");
        }
        init(pattern, null, new Integer(limit), new Integer(count), null, null);
    }

    /**
     * Construct a new {@code FileHandler}. The given name pattern is used as output filename, the
     * maximum file size is set to {@code limit}, the file count is initialized to {@code count} and
     * the append mode is set to {@code append}. This handler is configured to write to a rotating
     * set of count files, when the limit of bytes has been written to one output file, another file
     * will be opened instead.
     * 
     * @param pattern The name pattern for the output file.
     * @param limit The data amount limit in bytes of one output file, can not be negative.
     * @param count The maximum number of files to use, can not be less than one.
     * @param append The append mode.
     * @throws IOException if any I/O error occurs.
     * @throws IllegalArgumentException if {@code pattern} is empty, {@code limit < 0} or
     * {@code count < 1}.
     * @throws NullPointerException if {@code pattern} is {@code null}.
     */
    public FileHandler(String pattern, int limit, int count, boolean append) throws IOException {
        if (pattern == null || pattern.length() == 0) {
            throw new IllegalArgumentException("Pattern cannot be null or empty");
        }
        if (limit < 0 || count < 1) {
            throw new IllegalArgumentException("limit < 0 || count < 1");
        }
        init(pattern, Boolean.valueOf(append), new Integer(limit), new Integer(count), null, null);
    }
    
    public FileHandler(String pattern, int limit, int count, boolean append, int bufferSize, int dataVolumeLimit) throws IOException {
        if (pattern == null || pattern.length() == 0) {
            throw new IllegalArgumentException("Pattern cannot be null or empty");
        }
        if (limit < 0 || count < 1) {
            throw new IllegalArgumentException("limit < 0 || count < 1");
        }
        if (bufferSize < 0 || dataVolumeLimit < 0) {
            throw new IllegalArgumentException("bufferSize < 0 || dataVolumeLimit < 0");
        }
        init(pattern, Boolean.valueOf(append), new Integer(limit), new Integer(count), new Integer(bufferSize), new Integer(dataVolumeLimit));
    }

    /**
     * Flushes and closes all opened files.
     */
    public void close() {
        flush();
        
        if (mWriter != null) {
            try {
                mWriter.close();
            } catch (IOException ignore) {
            }
            mWriter = null;
        }
    }

    public void flush() {
        if (mWriter != null) {
            if (mDataVolumeLimit > 0) {
                mPreferences.edit().putInt(DATA_VOLUME, mDataVolume).commit();
            }
        
            try {
                mWriter.flush();
            } catch (IOException ignore) {
            }
        }
    }

    /**
     * Publish a {@code LogRecord}.
     * 
     * @param record The log record.
     */
    public synchronized void publish(LogRecord record) {
        String logMessage = record.toString();
        final int logMessageSize = logMessage.length() + CRLF.length();
        
        if (mDataVolumeLimit > 0) {
            if (mDataVolume + logMessageSize > mDataVolumeLimit) {
                return;
            }
        }
        
        if (mLimit > 0 && (mWriter.size() + logMessageSize) >= mLimit) {
            findNextGeneration();
        }
        
        try {
            mWriter.write(logMessage);
            mWriter.newLine();
            mFlushSize += logMessageSize;
            mDataVolume += logMessageSize;
            if (mFlushSize >= mBufferSize) {
                flush();
                mFlushSize = 0;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public synchronized boolean dump(String fileName) {
        if (fileName == null) {
            return false;
        }
        
        File tempFile = new File(mFiles[0].getParentFile(), Integer.toHexString(mFiles[0].getAbsolutePath().hashCode()) + ".tmp");
        try {
            tempFile.createNewFile();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            return false;
        } catch (IOException e) {
            e.printStackTrace();
            tempFile.delete();
            return false;
        }

        flush();

        Exception exception = null;
        BufferedReader reader = null;
        BufferedWriter writer = null;
        try {
            writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(tempFile, false)));
            for (int i = mCount - 1; i >= 0; i--) {
                if (!mFiles[i].exists()) {
                    continue;
                }

                File inputFile = mFiles[i];
                reader = new BufferedReader(new InputStreamReader(new FileInputStream(inputFile)));
                String logMessage = null;
                while ((logMessage = reader.readLine()) != null) {
                    writer.write(logMessage);
                    writer.newLine();
                }
                try {
                    reader.close();
                } catch (IOException ignore) {
                }
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            exception = e;
            return false;
        } catch (IOException e) {
            e.printStackTrace();
            exception = e;
            return false;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignore) {
                }
            }
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignore) {
                }
            }
            if (exception != null) {
                tempFile.delete();
            }
        }

        return tempFile.renameTo(new File(Environment.getLogDirectory(), fileName));
    }

    class Writer {
        private OutputStreamWriter mWriter;
        private long mSize;

        public Writer(File file, boolean append) throws IOException {
            if (!file.exists()) {
                if (file.getParentFile() != null && !file.getParentFile().exists()) {
                    file.getParentFile().mkdirs();
                }
                file.createNewFile();
            }
            
            if (mBufferSize > 0) {
                mWriter = new OutputStreamWriter(new BufferedOutputStream(new FileOutputStream(file, append), mBufferSize));
            } else {
                mWriter = new OutputStreamWriter(new BufferedOutputStream(new FileOutputStream(file, append)));
            }
            mSize = file.length();
        }
        
        public void write(String s) throws IOException {
            mWriter.write(s);
            mSize += s.length();
        }

        public void write(String s, int offset, int length) throws IOException {
            mWriter.write(s, offset, length);
            mSize += length;
        }

        public void close() throws IOException {
            mWriter.close();
        }

        public void flush() throws IOException {
            mWriter.flush();
        }

        public long size() {
            return mSize;
        }

        public void newLine() throws IOException {
            mWriter.write(CRLF);
            mSize += CRLF.length();
        }
    }
}
