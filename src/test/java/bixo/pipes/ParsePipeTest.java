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

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Iterator;
import java.util.Set;

import cascading.flow.hadoop.HadoopFlowConnector;
import cascading.flow.hadoop.HadoopFlowProcess;
import org.archive.io.ArchiveReader;
import org.archive.io.ArchiveReaderFactory;
import org.archive.io.ArchiveRecord;
import org.archive.io.ArchiveRecordHeader;
import org.junit.Test;

import bixo.datum.ContentBytes;
import bixo.datum.FetchedDatum;
import bixo.datum.HttpHeaders;
import bixo.datum.ParsedDatum;
import bixo.parser.SimpleParser;
import cascading.CascadingTestCase;
import cascading.flow.Flow;
import cascading.flow.FlowConnector;
import cascading.pipe.Pipe;
import cascading.scheme.hadoop.SequenceFile;
import cascading.tap.hadoop.Lfs;
import cascading.tuple.TupleEntryCollector;

@SuppressWarnings("deprecation")
public class ParsePipeTest extends CascadingTestCase {

    @SuppressWarnings("unchecked")
	@Test
    public void testParserPipe() throws Exception {

        Pipe pipe = new Pipe("parse_source");
        ParsePipe parserPipe = new ParsePipe(pipe, new SimpleParser());
        Lfs in = new Lfs(new SequenceFile(FetchedDatum.FIELDS), "build/test/ParserPipeTest/in", true);
        Lfs out = new Lfs(new SequenceFile(ParsedDatum.FIELDS), "build/test/ParserPipeTest/out", true);

        TupleEntryCollector write = in.openForWrite(new HadoopFlowProcess());

        ArchiveReader archiveReader = ArchiveReaderFactory.get("src/test/resources/someHtml.arc");
        Iterator<ArchiveRecord> iterator = archiveReader.iterator();
        int max = 300;
        int count = 0;
        int validRecords = 0;
        while (count++ < max && iterator.hasNext()) {
            ArchiveRecord archiveRecord = iterator.next();
            ArchiveRecordHeader header = archiveRecord.getHeader();
            String url = header.getUrl();

            String protocol = "";
            try {
                protocol = new URL(url).getProtocol();
            } catch (MalformedURLException e) {
                // Ignore and skip
            }

            if (protocol.equals("http")) {
                validRecords += 1;
                int contentOffset = header.getContentBegin();
                long totalLength = header.getLength();
                int contentLength = (int) totalLength - contentOffset;

                archiveRecord.skip(contentOffset);
                byte[] content = new byte[contentLength];
                archiveRecord.read(content);

                String mimetype = header.getMimetype();
                // The Arc headers != HTTP headers, but it's at least some data we can jam
                // into the FetchedDatum as a test. Note that the Arc headers will have value
                // types other than a long, so we have do to the conversion.
                HttpHeaders headers = new HttpHeaders();
                Set<String> keys = header.getHeaderFieldKeys();
                for (String key : keys) {
                    String value = header.getHeaderValue(key).toString();
                    headers.add(key, value);
                }
                
                FetchedDatum contentTuple = new FetchedDatum(url, url, System.currentTimeMillis(), headers, new ContentBytes(content), mimetype, 0);
                write.add(contentTuple.getTuple());
            }
        }

        write.close();
        FlowConnector flowConnector = new HadoopFlowConnector();
        Flow flow = flowConnector.connect(in, out, parserPipe);
        flow.complete();
        
        // Currently many of the docs fail parsing:
        // http://webtools.uiuc.edu/calendar/RSS?calId=504
        // http://www.cs.uiuc.edu/rss/cs-news.rss
        // http://fsl.cs.uiuc.edu/opensearch_desc.php
        // http://choices.cs.uiuc.edu/cache/computer-cover_files/r5tann01
        // http://choices.cs.uiuc.edu/cache/computer-cover_files/r5tann02
        // http://srg.cs.uiuc.edu/scgo/bfg_files/filelist.xml
        // http://srg.cs.uiuc.edu/scgo/bfg_files/pres.xml
        // http://fmc.cs.uiuc.edu/bg
        // TODO - dump out individual files, and figure out what's wrong with them.
        final int invalidDocs = 12;
        validateLength(flow, validRecords - invalidDocs);
    }

}
