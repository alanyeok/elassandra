/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.search.aggregations.bucket.histogram;

import org.apache.lucene.util.CollectionUtil;
import org.apache.lucene.util.PriorityQueue;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.rounding.Rounding;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.search.DocValueFormat;
import org.elasticsearch.search.aggregations.AggregationExecutionException;
import org.elasticsearch.search.aggregations.Aggregations;
import org.elasticsearch.search.aggregations.InternalAggregation;
import org.elasticsearch.search.aggregations.InternalAggregations;
import org.elasticsearch.search.aggregations.InternalMultiBucketAggregation;
import org.elasticsearch.search.aggregations.pipeline.PipelineAggregator;
import org.elasticsearch.search.aggregations.support.ValueType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

/**
 * TODO should be renamed to InternalNumericHistogram (see comment on {@link Histogram})?
 */
public class InternalHistogram<B extends InternalHistogram.Bucket> extends InternalMultiBucketAggregation<InternalHistogram<B>, B>
        implements Histogram {

    public static final Factory<Bucket> HISTOGRAM_FACTORY = new Factory<Bucket>();
    static final Type TYPE = new Type("histogram");

    public static class Bucket extends InternalMultiBucketAggregation.InternalBucket implements Histogram.Bucket {

        final long key;
        final long docCount;
        final InternalAggregations aggregations;
        private final transient boolean keyed;
        protected final transient DocValueFormat format;
        private final Factory<?> factory;

        public Bucket(long key, long docCount, boolean keyed, DocValueFormat format, Factory<?> factory,
                InternalAggregations aggregations) {
            this.format = format;
            this.keyed = keyed;
            this.factory = factory;
            this.key = key;
            this.docCount = docCount;
            this.aggregations = aggregations;
        }

        /**
         * Read from a stream.
         */
        public Bucket(StreamInput in, boolean keyed, DocValueFormat format, Factory<?> factory) throws IOException {
            this.format = format;
            this.keyed = keyed;
            this.factory = factory;
            key = in.readLong();
            docCount = in.readVLong();
            aggregations = InternalAggregations.readAggregations(in);
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeLong(key);
            out.writeVLong(docCount);
            aggregations.writeTo(out);
        }

        protected Factory<?> getFactory() {
            return factory;
        }

        @Override
        public String getKeyAsString() {
            return format.format(key);
        }

        @Override
        public Object getKey() {
            return key;
        }

        @Override
        public long getDocCount() {
            return docCount;
        }

        @Override
        public Aggregations getAggregations() {
            return aggregations;
        }

        @SuppressWarnings("unchecked")
        <B extends Bucket> B reduce(List<B> buckets, ReduceContext context) {
            List<InternalAggregations> aggregations = new ArrayList<>(buckets.size());
            long docCount = 0;
            for (Bucket bucket : buckets) {
                docCount += bucket.docCount;
                aggregations.add((InternalAggregations) bucket.getAggregations());
            }
            InternalAggregations aggs = InternalAggregations.reduce(aggregations, context);
            return (B) getFactory().createBucket(key, docCount, aggs, keyed, format);
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            String keyAsString = format.format(key);
            if (keyed) {
                builder.startObject(keyAsString);
            } else {
                builder.startObject();
            }
            if (format != DocValueFormat.RAW) {
                builder.field(CommonFields.KEY_AS_STRING, keyAsString);
            }
            builder.field(CommonFields.KEY, key);
            builder.field(CommonFields.DOC_COUNT, docCount);
            aggregations.toXContentInternal(builder, params);
            builder.endObject();
            return builder;
        }

        public DocValueFormat getFormatter() {
            return format;
        }

        public boolean getKeyed() {
            return keyed;
        }
    }

    static class EmptyBucketInfo {

        final Rounding rounding;
        final InternalAggregations subAggregations;
        final ExtendedBounds bounds;

        EmptyBucketInfo(Rounding rounding, InternalAggregations subAggregations) {
            this(rounding, subAggregations, null);
        }

        EmptyBucketInfo(Rounding rounding, InternalAggregations subAggregations, ExtendedBounds bounds) {
            this.rounding = rounding;
            this.subAggregations = subAggregations;
            this.bounds = bounds;
        }

        public static EmptyBucketInfo readFrom(StreamInput in) throws IOException {
            Rounding rounding = Rounding.Streams.read(in);
            InternalAggregations aggs = InternalAggregations.readAggregations(in);
            if (in.readBoolean()) {
                return new EmptyBucketInfo(rounding, aggs, new ExtendedBounds(in));
            }
            return new EmptyBucketInfo(rounding, aggs);
        }

        public static void writeTo(EmptyBucketInfo info, StreamOutput out) throws IOException {
            Rounding.Streams.write(info.rounding, out);
            info.subAggregations.writeTo(out);
            out.writeBoolean(info.bounds != null);
            if (info.bounds != null) {
                info.bounds.writeTo(out);
            }
        }

    }

    public static class Factory<B extends InternalHistogram.Bucket> {

        protected Factory() {
        }

        public Type type() {
            return TYPE;
        }

        public ValueType valueType() {
            return ValueType.NUMERIC;
        }

        public InternalHistogram<B> create(String name, List<B> buckets, InternalOrder order, long minDocCount,
                EmptyBucketInfo emptyBucketInfo, DocValueFormat formatter, boolean keyed,
                List<PipelineAggregator> pipelineAggregators,
                Map<String, Object> metaData) {
            return new InternalHistogram<>(name, buckets, order, minDocCount, emptyBucketInfo, formatter, keyed, this, pipelineAggregators,
                    metaData);
        }

        public InternalHistogram<B> create(List<B> buckets, InternalHistogram<B> prototype) {
            return new InternalHistogram<>(prototype.name, buckets, prototype.order, prototype.minDocCount, prototype.emptyBucketInfo,
                    prototype.format, prototype.keyed, this, prototype.pipelineAggregators(), prototype.metaData);
        }

        @SuppressWarnings("unchecked")
        public B createBucket(InternalAggregations aggregations, B prototype) {
            return (B) new Bucket(prototype.key, prototype.docCount, prototype.getKeyed(), prototype.format, this, aggregations);
        }

        @SuppressWarnings("unchecked")
        public B createBucket(Object key, long docCount, InternalAggregations aggregations, boolean keyed, DocValueFormat formatter) {
            if (key instanceof Number) {
                return (B) new Bucket(((Number) key).longValue(), docCount, keyed, formatter, this, aggregations);
            } else {
                throw new AggregationExecutionException("Expected key of type Number but got [" + key + "]");
            }
        }

        @SuppressWarnings("unchecked")
        protected B readBucket(StreamInput in, boolean keyed, DocValueFormat format) throws IOException {
            return (B) new Bucket(in, keyed, format, this);
        }
    }

    private final List<B> buckets;
    private final InternalOrder order;
    private final DocValueFormat format;
    private final boolean keyed;
    private final long minDocCount;
    private final EmptyBucketInfo emptyBucketInfo;
    private final Factory<B> factory;

    InternalHistogram(String name, List<B> buckets, InternalOrder order, long minDocCount, EmptyBucketInfo emptyBucketInfo,
            DocValueFormat formatter, boolean keyed, Factory<B> factory, List<PipelineAggregator> pipelineAggregators,
            Map<String, Object> metaData) {
        super(name, pipelineAggregators, metaData);
        this.buckets = buckets;
        this.order = order;
        assert (minDocCount == 0) == (emptyBucketInfo != null);
        this.minDocCount = minDocCount;
        this.emptyBucketInfo = emptyBucketInfo;
        this.format = formatter;
        this.keyed = keyed;
        this.factory = factory;
    }

    /**
     * Stream from a stream.
     */
    public InternalHistogram(StreamInput in) throws IOException {
        super(in);
        factory = resolveFactory(in.readString());
        order = InternalOrder.Streams.readOrder(in);
        minDocCount = in.readVLong();
        if (minDocCount == 0) {
            emptyBucketInfo = EmptyBucketInfo.readFrom(in);
        } else {
            emptyBucketInfo = null;
        }
        format = in.readNamedWriteable(DocValueFormat.class);
        keyed = in.readBoolean();
        buckets = in.readList(stream -> factory.readBucket(stream, keyed, format));
    }

    @SuppressWarnings("unchecked")
    protected static <B extends InternalHistogram.Bucket> Factory<B> resolveFactory(String factoryType) {
        if (factoryType.equals(InternalDateHistogram.TYPE.name())) {
            return (Factory<B>) new InternalDateHistogram.Factory();
        } else if (factoryType.equals(TYPE.name())) {
            return new Factory<>();
        } else {
            throw new IllegalStateException("Invalid histogram factory type [" + factoryType + "]");
        }
    }

    @Override
    protected void doWriteTo(StreamOutput out) throws IOException {
        out.writeString(factory.type().name());
        InternalOrder.Streams.writeOrder(order, out);
        out.writeVLong(minDocCount);
        if (minDocCount == 0) {
            EmptyBucketInfo.writeTo(emptyBucketInfo, out);
        }
        out.writeNamedWriteable(format);
        out.writeBoolean(keyed);
        out.writeList(buckets);
    }

    @Override
    public String getWriteableName() {
        return HistogramAggregationBuilder.NAME;
    }

    @Override
    public Type type() {
        return TYPE;
    }

    @Override
    public List<B> getBuckets() {
        return buckets;
    }

    public Factory<B> getFactory() {
        return factory;
    }

    public Rounding getRounding() {
        return emptyBucketInfo.rounding;
    }

    @Override
    public InternalHistogram<B> create(List<B> buckets) {
        return getFactory().create(buckets, this);
    }

    @Override
    public B createBucket(InternalAggregations aggregations, B prototype) {
        return getFactory().createBucket(aggregations, prototype);
    }

    private static class IteratorAndCurrent<B> {

        private final Iterator<B> iterator;
        private B current;

        IteratorAndCurrent(Iterator<B> iterator) {
            this.iterator = iterator;
            current = iterator.next();
        }

    }

    private List<B> reduceBuckets(List<InternalAggregation> aggregations, ReduceContext reduceContext) {

        final PriorityQueue<IteratorAndCurrent<B>> pq = new PriorityQueue<IteratorAndCurrent<B>>(aggregations.size()) {
            @Override
            protected boolean lessThan(IteratorAndCurrent<B> a, IteratorAndCurrent<B> b) {
                return a.current.key < b.current.key;
            }
        };
        for (InternalAggregation aggregation : aggregations) {
            @SuppressWarnings("unchecked")
            InternalHistogram<B> histogram = (InternalHistogram<B>) aggregation;
            if (histogram.buckets.isEmpty() == false) {
                pq.add(new IteratorAndCurrent<>(histogram.buckets.iterator()));
            }
        }

        List<B> reducedBuckets = new ArrayList<>();
        if (pq.size() > 0) {
            // list of buckets coming from different shards that have the same key
            List<B> currentBuckets = new ArrayList<>();
            long key = pq.top().current.key;

            do {
                final IteratorAndCurrent<B> top = pq.top();

                if (top.current.key != key) {
                    // the key changes, reduce what we already buffered and reset the buffer for current buckets
                    final B reduced = currentBuckets.get(0).reduce(currentBuckets, reduceContext);
                    if (reduced.getDocCount() >= minDocCount) {
                        reducedBuckets.add(reduced);
                    }
                    currentBuckets.clear();
                    key = top.current.key;
                }

                currentBuckets.add(top.current);

                if (top.iterator.hasNext()) {
                    final B next = top.iterator.next();
                    assert next.key > top.current.key : "shards must return data sorted by key";
                    top.current = next;
                    pq.updateTop();
                } else {
                    pq.pop();
                }
            } while (pq.size() > 0);

            if (currentBuckets.isEmpty() == false) {
                final B reduced = currentBuckets.get(0).reduce(currentBuckets, reduceContext);
                if (reduced.getDocCount() >= minDocCount) {
                    reducedBuckets.add(reduced);
                }
            }
        }

        return reducedBuckets;
    }

    private void addEmptyBuckets(List<B> list, ReduceContext reduceContext) {
        B lastBucket = null;
        ExtendedBounds bounds = emptyBucketInfo.bounds;
        ListIterator<B> iter = list.listIterator();

        // first adding all the empty buckets *before* the actual data (based on th extended_bounds.min the user requested)
        InternalAggregations reducedEmptySubAggs = InternalAggregations.reduce(Collections.singletonList(emptyBucketInfo.subAggregations),
                reduceContext);
        if (bounds != null) {
            B firstBucket = iter.hasNext() ? list.get(iter.nextIndex()) : null;
            if (firstBucket == null) {
                if (bounds.getMin() != null && bounds.getMax() != null) {
                    long key = bounds.getMin();
                    long max = bounds.getMax();
                    while (key <= max) {
                        iter.add(getFactory().createBucket(key, 0,
                                reducedEmptySubAggs,
                                keyed, format));
                        key = emptyBucketInfo.rounding.nextRoundingValue(key);
                    }
                }
            } else {
                if (bounds.getMin() != null) {
                    long key = bounds.getMin();
                    if (key < firstBucket.key) {
                        while (key < firstBucket.key) {
                            iter.add(getFactory().createBucket(key, 0,
                                    reducedEmptySubAggs,
                                    keyed, format));
                            key = emptyBucketInfo.rounding.nextRoundingValue(key);
                        }
                    }
                }
            }
        }

        // now adding the empty buckets within the actual data,
        // e.g. if the data series is [1,2,3,7] there're 3 empty buckets that will be created for 4,5,6
        while (iter.hasNext()) {
            B nextBucket = list.get(iter.nextIndex());
            if (lastBucket != null) {
                long key = emptyBucketInfo.rounding.nextRoundingValue(lastBucket.key);
                while (key < nextBucket.key) {
                    iter.add(getFactory().createBucket(key, 0,
                            reducedEmptySubAggs, keyed,
                            format));
                    key = emptyBucketInfo.rounding.nextRoundingValue(key);
                }
                assert key == nextBucket.key;
            }
            lastBucket = iter.next();
        }

        // finally, adding the empty buckets *after* the actual data (based on the extended_bounds.max requested by the user)
        if (bounds != null && lastBucket != null && bounds.getMax() != null && bounds.getMax() > lastBucket.key) {
            long key = emptyBucketInfo.rounding.nextRoundingValue(lastBucket.key);
            long max = bounds.getMax();
            while (key <= max) {
                iter.add(getFactory().createBucket(key, 0,
                        reducedEmptySubAggs, keyed,
                        format));
                key = emptyBucketInfo.rounding.nextRoundingValue(key);
            }
        }
    }

    @Override
    public InternalAggregation doReduce(List<InternalAggregation> aggregations, ReduceContext reduceContext) {
        List<B> reducedBuckets = reduceBuckets(aggregations, reduceContext);

        // adding empty buckets if needed
        if (minDocCount == 0) {
            addEmptyBuckets(reducedBuckets, reduceContext);
        }

        if (order == InternalOrder.KEY_ASC) {
            // nothing to do, data are already sorted since shards return
            // sorted buckets and the merge-sort performed by reduceBuckets
            // maintains order
        } else if (order == InternalOrder.KEY_DESC) {
            // we just need to reverse here...
            List<B> reverse = new ArrayList<>(reducedBuckets);
            Collections.reverse(reverse);
            reducedBuckets = reverse;
        } else {
            // sorted by sub-aggregation, need to fall back to a costly n*log(n) sort
            CollectionUtil.introSort(reducedBuckets, order.comparator());
        }

        return getFactory().create(getName(), reducedBuckets, order, minDocCount, emptyBucketInfo, format, keyed, pipelineAggregators(),
                getMetaData());
    }

    @Override
    public XContentBuilder doXContentBody(XContentBuilder builder, Params params) throws IOException {
        if (keyed) {
            builder.startObject(CommonFields.BUCKETS);
        } else {
            builder.startArray(CommonFields.BUCKETS);
        }
        for (B bucket : buckets) {
            bucket.toXContent(builder, params);
        }
        if (keyed) {
            builder.endObject();
        } else {
            builder.endArray();
        }
        return builder;
    }

}