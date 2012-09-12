/*
 * Copyright 2009-2012 Scale Unlimited
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
 *
 */
package bixo.tools;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Properties;

import org.apache.commons.io.IOUtils;
import org.apache.hadoop.mapred.JobConf;

import bixo.fetcher.BaseFetcher;
import bixo.operations.UrlLengthener;
import bixo.utils.ConfigUtils;
import cascading.flow.Flow;
import cascading.flow.FlowConnector;
import cascading.flow.hadoop.HadoopFlowConnector;
import cascading.operation.Debug;
import cascading.pipe.Each;
import cascading.pipe.Pipe;
import cascading.scheme.hadoop.TextLine;
import cascading.tap.hadoop.Lfs;
import cascading.tap.SinkTap;
import cascading.tuple.Fields;

import com.bixolabs.cascading.HadoopUtils;
import com.bixolabs.cascading.NullSinkTap;

public class LengthenUrlsTool {

    private static String readInputLine() throws IOException {
        InputStreamReader isr = new InputStreamReader(System.in);
        BufferedReader br = new BufferedReader(isr);
        
        try {
            return br.readLine();
        } finally {
            // TODO KKr - will this actually close System.in?
            // Should I reuse this buffered reader? Check out password masking code.
            // br.close();
        }
    }

    /**
     * @param args - URL to fetch, or path to file of URLs
     */
    public static void main(String[] args) {
        try {
            String url = null;
            if (args.length == 0) {
                System.out.print("URL to lengthen: ");
                url = readInputLine();
                if (url.length() == 0) {
                    System.exit(0);
                }

                if (!url.startsWith("http://")) {
                    url = "http://" + url;
                }
            } else if (args.length != 1) {
                System.out.print("A single URL or filename parameter is allowed");
                System.exit(0);
            } else {
                url = args[0];
            }

            String filename;
            if (!url.startsWith("http://")) {
                // It's a path to a file of URLs
                filename = url;
            } else {
                // We have a URL that we need to write to a temp file.
                File tempFile = File.createTempFile("LengthenUrlsTool", "txt");
                filename = tempFile.getAbsolutePath();
                FileWriter fw = new FileWriter(tempFile);
                IOUtils.write(url, fw);
                fw.close();
            }

            System.setProperty("bixo.root.level", "TRACE");
            // Uncomment this to see the wire log for HttpClient
            // System.setProperty("bixo.http.level", "DEBUG");

            BaseFetcher fetcher = UrlLengthener.makeFetcher(10, ConfigUtils.BIXO_TOOL_AGENT);

            Pipe pipe = new Pipe("urls");
            pipe = new Each(pipe, new UrlLengthener(fetcher));
            pipe = new Each(pipe, new Debug());

            Lfs sourceTap = new Lfs(new TextLine(new Fields("url")), filename);
            SinkTap sinkTap = new NullSinkTap(new Fields("url"));
            
            JobConf conf = HadoopUtils.getDefaultJobConf();
            Properties props = HadoopUtils.getDefaultProperties(LengthenUrlsTool.class, false, conf);
            FlowConnector flowConnector = new HadoopFlowConnector(props);
            Flow flow = flowConnector.connect(sourceTap, sinkTap, pipe);

            flow.complete();
        } catch (Exception e) {
            System.err.println("Exception running tool: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(-1);
        }
    }

}
