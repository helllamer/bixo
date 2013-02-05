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
package bixo.pipes;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;

import cascading.flow.hadoop.HadoopFlowConnector;
import org.apache.log4j.Logger;

import bixo.datum.FetchedDatum;
import bixo.datum.UrlDatum;
import bixo.fetcher.BaseFetcher;
import bixo.fetcher.simulation.FakeHttpFetcher;
import bixo.operations.BaseScoreGenerator;
import bixo.operations.FixedScoreGenerator;
import cascading.flow.Flow;
import cascading.flow.FlowConnector;
import cascading.flow.FlowProcess;
import cascading.operation.BaseOperation;
import cascading.operation.Function;
import cascading.operation.FunctionCall;
import cascading.pipe.Each;
import cascading.pipe.Pipe;
import cascading.scheme.hadoop.TextLine;
import cascading.tap.hadoop.Hfs;
import cascading.tap.hadoop.Lfs;
import cascading.tap.Tap;
import cascading.tuple.Fields;

import com.scaleunlimited.cascading.NullContext;

public class RunFakeFetchPipe {
    private static final Logger LOGGER = Logger.getLogger(RunFakeFetchPipe.class);

    @SuppressWarnings("serial")
    private static class CreateUrlFunction extends BaseOperation<NullContext> implements Function<NullContext> {

        public CreateUrlFunction() {
            super(UrlDatum.FIELDS);
        }

        public void operate(FlowProcess process, FunctionCall<NullContext> funcCall) {
            String urlAsString = funcCall.getArguments().getString("line");
            try {
                URL url = new URL(urlAsString);

                UrlDatum urlDatum = new UrlDatum(url.toString());

                funcCall.getOutputCollector().add(urlDatum.getTuple());
            } catch (MalformedURLException e) {
                LOGGER.warn("Invalid URL: " + urlAsString);
                // throw new RuntimeException("Invalid URL: " + urlAsString, e);
            }
        }
    }

    /**
     * @param args
     */
    public static void main(String[] args) {
        try {
            URL path = RunFakeFetchPipe.class.getResource("/" + args[0]);
            if (path == null) {
                System.err.println("File not found on classpath: " + args[0]);
                System.exit(-1);
            }

            File inputFile = new File(path.getFile());
            Tap in = new Lfs(new TextLine(), inputFile.getCanonicalPath());

            Pipe importPipe = new Each("url importer", new Fields("line"), new CreateUrlFunction());

            BaseScoreGenerator scorer = new FixedScoreGenerator();
            BaseFetcher fetcher = new FakeHttpFetcher(true, 10);
            FetchPipe fetchPipe = new FetchPipe(importPipe, scorer, fetcher, 1);

            // Create the output, which is a dual file sink tap.
            String outputPath = "build/test/RunFakeFetchPipe/dual";
            Tap status = new Hfs(new TextLine(), outputPath + "/status", true);
            Tap content = new Hfs(new TextLine(null, FetchedDatum.FIELDS), outputPath + "/content", true);
            
            // Finally we can run it.
            FlowConnector flowConnector = new HadoopFlowConnector();
            Flow flow = flowConnector.connect(in, FetchPipe.makeSinkMap(status, content), fetchPipe);
            flow.complete();
        } catch (Throwable t) {
            System.err.println("Exception running fake fetch pipe assembly: " + t.getMessage());
            t.printStackTrace(System.err);
            System.exit(-1);
        }

    }

}
