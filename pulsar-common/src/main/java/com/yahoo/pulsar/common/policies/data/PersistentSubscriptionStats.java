/**
 * Copyright 2016 Yahoo Inc.
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
package com.yahoo.pulsar.common.policies.data;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.List;

import com.google.common.collect.Lists;
import com.yahoo.pulsar.common.api.proto.PulsarApi.CommandSubscribe.SubType;

/**
 */
public class PersistentSubscriptionStats {
    /** Total rate of messages delivered on this subscription. msg/s */
    public double msgRateOut;

    /** Total throughput delivered on this subscription. bytes/s */
    public double msgThroughputOut;

    /** Number of messages in the subscription backlog */
    public long msgBacklog;

    /** whether this subscription is Exclusive or Shared or Failover */
    public SubType type;

    /** Total rate of messages expired on this subscription. msg/s */
    public double msgRateExpired;

    /** List of connected consumers on this subscription w/ their stats */
    public List<ConsumerStats> consumers;

    public PersistentSubscriptionStats() {
        this.consumers = Lists.newArrayList();
    }

    public void reset() {
        msgRateOut = 0;
        msgThroughputOut = 0;
        msgBacklog = 0;
        msgRateExpired = 0;
        consumers.clear();
    }

    // if the stats are added for the 1st time, we will need to make a copy of these stats and add it to the current
    // stats
    public PersistentSubscriptionStats add(PersistentSubscriptionStats stats) {
        checkNotNull(stats);
        this.msgRateOut += stats.msgRateOut;
        this.msgThroughputOut += stats.msgThroughputOut;
        this.msgBacklog += stats.msgBacklog;
        this.msgRateExpired += stats.msgRateExpired;
        if (this.consumers.size() != stats.consumers.size()) {
            for (int i = 0; i < stats.consumers.size(); i++) {
                ConsumerStats consumerStats = new ConsumerStats();
                this.consumers.add(consumerStats.add(stats.consumers.get(i)));
            }
        } else {
            for (int i = 0; i < stats.consumers.size(); i++) {
                this.consumers.get(i).add(stats.consumers.get(i));
            }
        }
        return this;
    }
}
