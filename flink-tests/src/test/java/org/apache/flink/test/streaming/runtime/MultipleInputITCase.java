/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.test.streaming.runtime;

import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.typeinfo.BasicTypeInfo;
import org.apache.flink.api.common.typeutils.base.LongSerializer;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.runtime.state.StateInitializationContext;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.MultipleConnectedStreams;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.operators.AbstractInput;
import org.apache.flink.streaming.api.operators.AbstractStreamOperatorFactory;
import org.apache.flink.streaming.api.operators.AbstractStreamOperatorV2;
import org.apache.flink.streaming.api.operators.Input;
import org.apache.flink.streaming.api.operators.MultipleInputStreamOperator;
import org.apache.flink.streaming.api.operators.StreamOperator;
import org.apache.flink.streaming.api.operators.StreamOperatorParameters;
import org.apache.flink.streaming.api.transformations.KeyedMultipleInputTransformation;
import org.apache.flink.streaming.api.transformations.MultipleInputTransformation;
import org.apache.flink.streaming.api.transformations.UnionTransformation;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.test.streaming.runtime.util.TestListResultSink;
import org.apache.flink.test.util.AbstractTestBaseJUnit4;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.apache.flink.util.Preconditions.checkState;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.junit.Assert.assertEquals;

/** Integration tests for {@link MultipleInputStreamOperator}. */
@SuppressWarnings("serial")
public class MultipleInputITCase extends AbstractTestBaseJUnit4 {

    @Test
    public void testBasicProcessing() throws Exception {
        testNonKeyed(false);
    }

    @Test
    public void testUnion() throws Exception {
        testNonKeyed(true);
    }

    public void testNonKeyed(boolean withUnion) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);

        TestListResultSink<Long> resultSink = new TestListResultSink<>();

        DataStream<Long> source1 = env.fromData(1L, 10L);
        DataStream<Long> source2 = env.fromData(2L, 11L);
        DataStream<String> source3 = env.fromData("42", "44");

        MultipleInputTransformation<Long> multipleInput =
                new MultipleInputTransformation<>(
                        "My Operator",
                        new SumAllInputOperatorFactory(withUnion ? 2 : 3),
                        BasicTypeInfo.LONG_TYPE_INFO,
                        1);

        MultipleInputTransformation<Long> multipleInputTransformation;
        if (withUnion) {
            UnionTransformation<Long> union =
                    new UnionTransformation<>(
                            Arrays.asList(
                                    source1.getTransformation(), source2.getTransformation()));

            multipleInputTransformation = multipleInput.addInput(union);
        } else {
            multipleInputTransformation =
                    multipleInput
                            .addInput(source1.getTransformation())
                            .addInput(source2.getTransformation());
        }

        env.addOperator(multipleInputTransformation.addInput(source3.getTransformation()));

        new MultipleConnectedStreams(env).transform(multipleInput).addSink(resultSink);

        env.execute();

        List<Long> result = resultSink.getResult();
        Collections.sort(result);
        long actualSum = result.get(result.size() - 1);
        assertEquals(1 + 10 + 2 + 11 + 42 + 44, actualSum);
    }

    @Test
    public void testKeyedState() throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);

        TestListResultSink<Long> resultSink = new TestListResultSink<>();

        DataStream<Long> source1 = env.fromData(0L, 3L);
        DataStream<Long> source2 = env.fromData(13L, 16L);
        DataStream<Long> source3 = env.fromData(101L, 104L);

        KeyedMultipleInputTransformation<Long> transform =
                new KeyedMultipleInputTransformation<>(
                        "My Operator",
                        new KeyedSumMultipleInputOperatorFactory(),
                        BasicTypeInfo.LONG_TYPE_INFO,
                        1,
                        BasicTypeInfo.LONG_TYPE_INFO);
        KeySelector<Long, Long> keySelector = (KeySelector<Long, Long>) value -> value % 3;

        env.addOperator(
                transform
                        .addInput(source1.getTransformation(), keySelector)
                        .addInput(source2.getTransformation(), keySelector)
                        .addInput(source3.getTransformation(), keySelector));

        new MultipleConnectedStreams(env).transform(transform).addSink(resultSink);

        env.execute();

        List<Long> result = resultSink.getResult();
        Collections.sort(result);
        assertThat(result, contains(0L, 3L, 13L, 13L + 16L, 101L, 101L + 104L));
    }

    private static class KeyedSumMultipleInputOperator extends AbstractStreamOperatorV2<Long>
            implements MultipleInputStreamOperator<Long> {

        private ValueState<Long> sumState;

        public KeyedSumMultipleInputOperator(StreamOperatorParameters<Long> parameters) {
            super(parameters, 3);
        }

        @Override
        public void initializeState(StateInitializationContext context) throws Exception {
            super.initializeState(context);

            sumState =
                    context.getKeyedStateStore()
                            .getState(
                                    new ValueStateDescriptor<>(
                                            "sum-state", LongSerializer.INSTANCE));
        }

        @Override
        public List<Input> getInputs() {
            return Arrays.asList(
                    new KeyedSumInput(this, 1),
                    new KeyedSumInput(this, 2),
                    new KeyedSumInput(this, 3));
        }

        private class KeyedSumInput extends AbstractInput<Long, Long> {
            public KeyedSumInput(AbstractStreamOperatorV2<Long> owner, int inputId) {
                super(owner, inputId);
            }

            @Override
            public void processElement(StreamRecord<Long> element) throws Exception {
                if (sumState.value() == null) {
                    sumState.update(0L);
                }
                sumState.update(sumState.value() + element.getValue());
                output.collect(element.replace(sumState.value()));
            }
        }
    }

    private static class KeyedSumMultipleInputOperatorFactory
            extends AbstractStreamOperatorFactory<Long> {
        @Override
        public <T extends StreamOperator<Long>> T createStreamOperator(
                StreamOperatorParameters<Long> parameters) {
            return (T) new KeyedSumMultipleInputOperator(parameters);
        }

        @Override
        public Class<? extends StreamOperator<Long>> getStreamOperatorClass(
                ClassLoader classLoader) {
            return KeyedSumMultipleInputOperator.class;
        }
    }

    /** 3 input operator that sums all of it inputs. */
    public static class SumAllInputOperator extends AbstractStreamOperatorV2<Long>
            implements MultipleInputStreamOperator<Long> {
        private final int numberOfInputs;
        private long sum;

        public SumAllInputOperator(StreamOperatorParameters<Long> parameters, int numberOfInputs) {
            super(parameters, numberOfInputs);
            this.numberOfInputs = numberOfInputs;
        }

        @Override
        public List<Input> getInputs() {
            checkState(numberOfInputs <= 3);
            return Arrays.<Input>asList(
                            new SumInput<Integer>(this, 1),
                            new SumInput<Long>(this, 2),
                            new SumInput<String>(this, 3))
                    .subList(0, numberOfInputs);
        }

        /** Summing input for {@link SumAllInputOperator}. */
        public class SumInput<T> extends AbstractInput<T, Long> {
            public SumInput(AbstractStreamOperatorV2<Long> owner, int inputId) {
                super(owner, inputId);
            }

            @Override
            public void processElement(StreamRecord<T> element) throws Exception {
                sum += Long.valueOf(element.getValue().toString());
                output.collect(new StreamRecord<>(sum));
            }
        }
    }

    /** Factory for {@link SumAllInputOperator}. */
    public static class SumAllInputOperatorFactory extends AbstractStreamOperatorFactory<Long> {
        private int numberOfInputs;

        public SumAllInputOperatorFactory(int numberOfInputs) {
            this.numberOfInputs = numberOfInputs;
        }

        @Override
        public <T extends StreamOperator<Long>> T createStreamOperator(
                StreamOperatorParameters<Long> parameters) {
            return (T) new SumAllInputOperator(parameters, numberOfInputs);
        }

        @Override
        public Class<? extends StreamOperator> getStreamOperatorClass(ClassLoader classLoader) {
            return SumAllInputOperator.class;
        }
    }
}
