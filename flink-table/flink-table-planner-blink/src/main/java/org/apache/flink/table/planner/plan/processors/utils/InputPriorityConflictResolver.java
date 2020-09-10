/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.planner.plan.processors.utils;

import org.apache.flink.annotation.Internal;
import org.apache.flink.streaming.api.transformations.ShuffleMode;
import org.apache.flink.table.planner.plan.nodes.exec.ExecEdge;
import org.apache.flink.table.planner.plan.nodes.exec.ExecNode;
import org.apache.flink.table.planner.plan.nodes.physical.batch.BatchExecExchange;
import org.apache.flink.table.planner.plan.trait.FlinkRelDistribution;

import org.apache.calcite.rel.RelNode;

import java.util.Collections;
import java.util.List;

/**
 * Subclass of the {@link InputPriorityGraphGenerator}.
 *
 * <p>This class resolve conflicts by inserting a {@link BatchExecExchange} into the conflicting input.
 */
@Internal
public class InputPriorityConflictResolver extends InputPriorityGraphGenerator {

	private final ShuffleMode shuffleMode;

	/**
	 * Create a {@link InputPriorityConflictResolver} for the given {@link ExecNode} graph.
	 *
	 * @param roots the first layer of nodes on the output side of the graph
	 * @param safeDamBehavior when checking for conflicts we'll ignore the edges with
	 *                        {@link ExecEdge.DamBehavior} stricter or equal than this
	 * @param shuffleMode when a conflict occurs we'll insert an {@link BatchExecExchange} node
	 * 	                  with this shuffleMode to resolve conflict
	 */
	public InputPriorityConflictResolver(
			List<ExecNode<?, ?>> roots,
			ExecEdge.DamBehavior safeDamBehavior,
			ShuffleMode shuffleMode) {
		super(roots, Collections.emptySet(), safeDamBehavior);
		this.shuffleMode = shuffleMode;
	}

	public void detectAndResolve() {
		createTopologyGraph();
	}

	@Override
	protected void resolveInputPriorityConflict(ExecNode<?, ?> node, int conflictInput) {
		ExecNode<?, ?> conflictNode = node.getInputNodes().get(conflictInput);
		if (conflictNode instanceof BatchExecExchange) {
			BatchExecExchange exchange = (BatchExecExchange) conflictNode;
			exchange.setRequiredShuffleMode(shuffleMode);
		} else {
			node.replaceInputNode(conflictInput, (ExecNode) createExchange(node, conflictInput));
		}
	}

	private BatchExecExchange createExchange(ExecNode<?, ?> node, int idx) {
		RelNode inputRel = (RelNode) node.getInputNodes().get(idx);

		FlinkRelDistribution distribution;
		ExecEdge.RequiredShuffle requiredShuffle = node.getInputEdges().get(idx).getRequiredShuffle();
		if (requiredShuffle.getType() == ExecEdge.ShuffleType.HASH) {
			distribution = FlinkRelDistribution.hash(requiredShuffle.getKeys(), true);
		} else if (requiredShuffle.getType() == ExecEdge.ShuffleType.BROADCAST) {
			// should not occur
			throw new IllegalStateException(
				"Trying to resolve input priority conflict on broadcast side. This is not expected.");
		} else if (requiredShuffle.getType() == ExecEdge.ShuffleType.SINGLETON) {
			distribution = FlinkRelDistribution.SINGLETON();
		} else {
			distribution = FlinkRelDistribution.ANY();
		}

		BatchExecExchange exchange = new BatchExecExchange(
			inputRel.getCluster(),
			inputRel.getTraitSet().replace(distribution),
			inputRel,
			distribution);
		exchange.setRequiredShuffleMode(shuffleMode);
		return exchange;
	}
}