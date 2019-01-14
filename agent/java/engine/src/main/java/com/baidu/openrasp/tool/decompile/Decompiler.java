/*
 * Copyright 2017-2018 Baidu Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.baidu.openrasp.tool.decompile;

import com.baidu.openrasp.tool.LRUCache;
import com.strobel.assembler.metadata.*;
import com.strobel.decompiler.DecompilationOptions;
import com.strobel.decompiler.DecompilerSettings;
import com.strobel.decompiler.languages.BytecodeOutputOptions;
import com.strobel.decompiler.languages.java.JavaFormattingOptions;
import org.apache.commons.io.IOUtils;
import org.apache.log4j.Logger;

import java.io.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @description: 反编译工具类
 * @author: anyang
 * @create: 2018/10/18 20:50
 */
public class Decompiler {
    private static final Logger LOGGER = Logger.getLogger(Decompiler.class.getName());

    private static LRUCache<String, String> decompileCache = new LRUCache<String, String>(100);

    private static String getDecompilerString(InputStream in, String className) throws Exception {
        DecompilerSettings settings = new DecompilerSettings();
        settings.setBytecodeOutputOptions(BytecodeOutputOptions.createVerbose());
        if (settings.getJavaFormattingOptions() == null) {
            settings.setJavaFormattingOptions(JavaFormattingOptions.createDefault());
        }
        settings.setShowDebugLineNumbers(true);
        DecompilationOptions decompilationOptions = new DecompilationOptions();
        decompilationOptions.setSettings(settings);
        decompilationOptions.setFullDecompilation(true);
        ArrayTypeLoader typeLoader = new ArrayTypeLoader(IOUtils.toByteArray(in));
        MetadataSystem metadataSystem = new MetadataSystem(typeLoader);
        className = className.replace(".", "/");
        TypeReference type = metadataSystem.lookupType(className);
        DecompilerProvider newProvider = new DecompilerProvider();
        newProvider.setDecompilerReferences(settings, decompilationOptions);
        newProvider.setType(type.resolve());
        newProvider.generateContent();
        return newProvider.getTextContent();
    }

    private static String matchStringByRegularExpression(String line, int lineNumber) {
        String regex = ".*\\/\\*[E|S]L:" + lineNumber + "\\*\\/.*";
        Pattern p = Pattern.compile(regex);
        Matcher m = p.matcher(line);
        if (m.find()) {
            return m.group().trim().replaceAll("\\/\\/[^\\n]*|\\/\\*([^\\*^\\/]*|[\\*^\\/*]*|[^\\**\\/]*)*\\*+\\/", "");
        }
        return "";
    }

    public static ArrayList<String> getAlarmPoint(StackTraceElement[] stackTraceElements, String appBasePath) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        StackTraceFilter traceFilter = new StackTraceFilter();
        traceFilter.handleStackTrace(stackTraceElements);
        for (Map.Entry<String, Integer> entry : traceFilter.class_lineNumber.entrySet()) {
            String description = entry.getKey() + "." + traceFilter.class_method.get(entry.getKey()) + "(" + entry.getValue() + ")";
            if (decompileCache.isContainsKey(description)) {
                result.put(description, decompileCache.get(description));
                continue;
            }
            try {
                String simpleName = entry.getKey().substring(entry.getKey().lastIndexOf(".") + 1) + ".class";
                Class clazz = Thread.currentThread().getContextClassLoader().loadClass(entry.getKey());
                String src = getDecompilerString(clazz.getResourceAsStream(simpleName), entry.getKey());
                if (!src.isEmpty()) {
                    boolean isFind = false;
                    for (String line : src.split(System.getProperty("line.separator"))) {
                        String matched = Decompiler.matchStringByRegularExpression(line, traceFilter.class_lineNumber.get(entry.getKey()));
                        if (!"".equals(matched)) {
                            isFind = true;
                            result.put(description, matched);
                            decompileCache.put(description, matched);
                            break;
                        }
                    }
                    if (!isFind) {
                        result.put(description, "");
                    }
                } else {
                    result.put(description, "");
                }
            } catch (Exception e) {
                result.put(description, "");
            }
        }
        ArrayList<String> list = new ArrayList<String>(result.size());
        for (Map.Entry<String, String> entry : result.entrySet()) {
            list.add(entry.getValue());
        }
        return list;
    }
}