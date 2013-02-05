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
package bixo.operations;

import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Queue;

import org.apache.log4j.Logger;

import bixo.datum.GroupedUrlDatum;
import bixo.datum.ScoredUrlDatum;
import bixo.datum.UrlStatus;
import bixo.fetcher.BaseFetcher;
import bixo.hadoop.FetchCounters;
import bixo.robots.BaseRobotRules;
import bixo.robots.BaseRobotsParser;
import bixo.robots.RobotUtils;
import bixo.utils.DomainInfo;
import bixo.utils.DomainNames;
import bixo.utils.GroupingKey;
import cascading.tuple.TupleEntryCollector;

import com.scaleunlimited.cascading.LoggingFlowProcess;

public class ProcessRobotsTask implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(ProcessRobotsTask.class);

    private String _protocolAndDomain;
    private BaseScoreGenerator _scorer;
    private Queue<GroupedUrlDatum> _urls;
    private BaseFetcher _fetcher;
    private TupleEntryCollector _collector;
    private BaseRobotsParser _parser;
    private LoggingFlowProcess _flowProcess;

    public ProcessRobotsTask(String protocolAndDomain, BaseScoreGenerator scorer, Queue<GroupedUrlDatum> urls, BaseFetcher fetcher, 
                    BaseRobotsParser parser, TupleEntryCollector collector, LoggingFlowProcess flowProcess) {
        _protocolAndDomain = protocolAndDomain;
        _scorer = scorer;
        _urls = urls;
        _fetcher = fetcher;
        _parser = parser;
        _collector = collector;
        _flowProcess = flowProcess;
    }

    /**
     * Clear out the queue by outputting all entries with <groupingKey>.
     * 
     * We do this to empty the queue when there's some kind of error.
     * 
     * @param urls Queue of URLs to empty out
     * @param groupingKey grouping key to use for all entries.
     * @param collector tuple output collector
     */
    public static void emptyQueue(Queue<GroupedUrlDatum> urls, String groupingKey, TupleEntryCollector collector) {
        GroupedUrlDatum datum;
        while ((datum = urls.poll()) != null) {
            ScoredUrlDatum scoreUrl = new ScoredUrlDatum(datum.getUrl(), groupingKey, UrlStatus.UNFETCHED, 1.0);
            scoreUrl.setPayload(datum.getPayload());
            // TODO KKr - move synchronization up, to avoid lots of contention with other threads?
            synchronized (collector) {
                collector.add(scoreUrl.getTuple());
            }
        }
    }

    /* (non-Javadoc)
     * @see java.lang.Runnable#run()
     * 
     * Get robots.txt for the domain, and use it to generate a new grouping key
     * for all of the URLs that provides the count & crawl delay (or deferred/blocked)
     * values that we need.
     */
    public void run() {
        _flowProcess.increment(FetchCounters.DOMAINS_PROCESSING, 1);

        try {
            DomainInfo domainInfo = new DomainInfo(_protocolAndDomain);
            if (!domainInfo.isValidHostAddress()) {
                throw new UnknownHostException(_protocolAndDomain);
            }
            
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace(String.format("Resolved %s to %s", _protocolAndDomain, domainInfo.getHostAddress()));
            }
            
            String domain = domainInfo.getDomain();
            String pld = DomainNames.getPLD(domain);
            if (!_scorer.isGoodDomain(domain, pld)) {
                _flowProcess.increment(FetchCounters.DOMAINS_SKIPPED, 1);
                _flowProcess.increment(FetchCounters.URLS_SKIPPED, _urls.size());
                
                LOGGER.debug("Skipping URLs from not-good domain: " + domain);
                
                emptyQueue(_urls, GroupingKey.SKIPPED_GROUPING_KEY, _collector);
            } else {
                BaseRobotRules robotRules = RobotUtils.getRobotRules(_fetcher, _parser, new URL(domainInfo.getProtocolAndDomain() + "/robots.txt"));

                String validKey = null;
                boolean isDeferred = robotRules.isDeferVisits();
                if (isDeferred) {
                    LOGGER.debug("Deferring visits to URLs from " + domainInfo.getDomain());
                    _flowProcess.increment(FetchCounters.DOMAINS_DEFERRED, 1);
                } else {
                    validKey = GroupingKey.makeGroupingKey(domainInfo.getHostAddress(), robotRules.getCrawlDelay());
                    _flowProcess.increment(FetchCounters.DOMAINS_FINISHED, 1);
                }

                // Use the same key for every URL from this domain
                GroupedUrlDatum datum;
                while ((datum = _urls.poll()) != null) {
                    ScoredUrlDatum scoreUrl;
                    FetchCounters counter;
                    String url = datum.getUrl();

                    if (isDeferred) {
                        counter = FetchCounters.URLS_DEFERRED;
                        scoreUrl = new ScoredUrlDatum(url, GroupingKey.DEFERRED_GROUPING_KEY, UrlStatus.SKIPPED_DEFERRED, 0.0);
                    } else if (!robotRules.isAllowed(url)) {
                        counter = FetchCounters.URLS_BLOCKED;
                        scoreUrl = new ScoredUrlDatum(url, GroupingKey.BLOCKED_GROUPING_KEY, UrlStatus.SKIPPED_BLOCKED, 0.0);
                    } else {
                        counter = FetchCounters.URLS_ACCEPTED;
                        double score = _scorer.generateScore(domain, pld, datum);
                        scoreUrl = new ScoredUrlDatum(url, validKey, UrlStatus.UNFETCHED, score);
                    }
                    
                    scoreUrl.setPayload(datum.getPayload());
                    _flowProcess.increment(counter, 1);

                    // collectors aren't thread safe
                    synchronized (_collector) {
                        _collector.add(scoreUrl.getTuple());
                    }
                }
            }
        } catch (UnknownHostException e) {
            LOGGER.debug("Unknown host: " + _protocolAndDomain);
            _flowProcess.increment(FetchCounters.DOMAINS_REJECTED, 1);
            _flowProcess.increment(FetchCounters.URLS_REJECTED, _urls.size());
            emptyQueue(_urls, GroupingKey.UNKNOWN_HOST_GROUPING_KEY, _collector);
        } catch (MalformedURLException e) {
            LOGGER.debug("Invalid URL: " + _protocolAndDomain);
            _flowProcess.increment(FetchCounters.DOMAINS_REJECTED, 1);
            _flowProcess.increment(FetchCounters.URLS_REJECTED, _urls.size());
            emptyQueue(_urls, GroupingKey.INVALID_URL_GROUPING_KEY, _collector);
        } catch (URISyntaxException e) {
            LOGGER.debug("Invalid URI: " + _protocolAndDomain);
            _flowProcess.increment(FetchCounters.DOMAINS_REJECTED, 1);
            _flowProcess.increment(FetchCounters.URLS_REJECTED, _urls.size());
            emptyQueue(_urls, GroupingKey.INVALID_URL_GROUPING_KEY, _collector);
        } catch (Exception e) {
            LOGGER.warn("Exception processing " + _protocolAndDomain, e);
            _flowProcess.increment(FetchCounters.DOMAINS_REJECTED, 1);
            _flowProcess.increment(FetchCounters.URLS_REJECTED, _urls.size());
            emptyQueue(_urls, GroupingKey.INVALID_URL_GROUPING_KEY, _collector);
        } finally {
            _flowProcess.decrement(FetchCounters.DOMAINS_PROCESSING, 1);
        }
    }

}
