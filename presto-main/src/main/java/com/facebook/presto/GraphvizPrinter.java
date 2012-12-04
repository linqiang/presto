package com.facebook.presto;

import com.facebook.presto.metadata.ColumnHandle;
import com.facebook.presto.sql.ExpressionFormatter;
import com.facebook.presto.sql.compiler.Symbol;
import com.facebook.presto.sql.planner.AggregationNode;
import com.facebook.presto.sql.planner.ExchangeNode;
import com.facebook.presto.sql.planner.FilterNode;
import com.facebook.presto.sql.planner.JoinNode;
import com.facebook.presto.sql.planner.LimitNode;
import com.facebook.presto.sql.planner.OutputPlan;
import com.facebook.presto.sql.planner.PlanFragment;
import com.facebook.presto.sql.planner.PlanNode;
import com.facebook.presto.sql.planner.PlanVisitor;
import com.facebook.presto.sql.planner.ProjectNode;
import com.facebook.presto.sql.planner.TableScan;
import com.facebook.presto.sql.planner.TopNNode;
import com.facebook.presto.sql.tree.Expression;
import com.facebook.presto.sql.tree.FunctionCall;
import com.facebook.presto.sql.tree.QualifiedNameReference;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;

import java.util.List;
import java.util.Map;

import static java.lang.String.format;

public class GraphvizPrinter
{
    public static String print(List<PlanFragment> fragments)
    {
        Map<Integer, PlanFragment> fragmentsById = Maps.uniqueIndex(fragments, new Function<PlanFragment, Integer>()
        {
            @Override
            public Integer apply(PlanFragment input)
            {
                return input.getId();
            }
        });

        StringBuilder output = new StringBuilder();
        output.append("digraph Plan {")
                .append('\n');

        for (PlanFragment fragment : fragments) {
            printFragmentNodes(output, fragment);
        }

        for (PlanFragment fragment : fragments) {
            fragment.getRoot().accept(new EdgePrinter(output, fragmentsById), null);
        }

        output.append("}")
                .append('\n');

        return output.toString();
    }

    private static void printFragmentNodes(StringBuilder output, PlanFragment fragment)
    {
        String clusterId = "cluster_" + fragment.getId();
        output.append("subgraph ")
                .append(clusterId)
                .append(" {")
                .append('\n');


        output.append(format("label = \"%s\"", fragment.isPartitioned() ? "partitioned" : "unpartitioned"))
                .append('\n');

        PlanNode plan = fragment.getRoot();
        plan.accept(new NodePrinter(output), null);

        output.append("}")
                .append('\n');
    }

    private static class NodePrinter
            extends PlanVisitor<Void, Void>
    {
        private final StringBuilder output;

        public NodePrinter(StringBuilder output)
        {
            this.output = output;
        }

        @Override
        protected Void visitPlan(PlanNode node, Void context)
        {
            output.append(format("/* plannode_%s: %s*/", System.identityHashCode(node), node.getClass().getName()))
                    .append('\n');

            return null;
        }

        @Override
        public Void visitExchange(ExchangeNode node, Void context)
        {
            printNode(node, "Exchange 1:N");

            return null;
        }

        @Override
        public Void visitAggregation(AggregationNode node, Void context)
        {
            StringBuilder builder = new StringBuilder();
            for (Map.Entry<Symbol, FunctionCall> entry : node.getAggregations().entrySet()) {
                builder.append(format("%s := %s\\n", entry.getKey(), ExpressionFormatter.toString(entry.getValue())));
            }

            printNode(node, String.format("Aggregate[%s]", node.getStep()), builder.toString());

            return node.getSource().accept(this, context);
        }

        @Override
        public Void visitFilter(FilterNode node, Void context)
        {
            String expression = ExpressionFormatter.toString(node.getPredicate());
            expression = expression.replace(">", "\\>");
            expression = expression.replace("<", "\\<");

            printNode(node, "Filter", expression);

            return node.getSource().accept(this, context);
        }

        @Override
        public Void visitProject(ProjectNode node, Void context)
        {
            StringBuilder builder = new StringBuilder();
            for (Map.Entry<Symbol, Expression> entry : node.getOutputMap().entrySet()) {
                if (entry.getValue() instanceof QualifiedNameReference && ((QualifiedNameReference) entry.getValue()).getName().equals(entry.getKey().toQualifiedName())) {
                    // skip identity assignments
                    continue;
                }
                String expression = ExpressionFormatter.toString(entry.getValue());

                builder.append(format("%s := %s\\n", entry.getKey(), expression));
            }

            printNode(node, "Project", builder.toString());

            return node.getSource().accept(this, context);
        }

        @Override
        public Void visitTopN(final TopNNode node, Void context)
        {
            Iterable<String> keys = Iterables.transform(node.getOrderBy(), new Function<Symbol, String>()
            {
                @Override
                public String apply(Symbol input)
                {
                    return input + " " + node.getOrderings().get(input);
                }
            });

            printNode(node, format("TopN[%s]", node.getCount()), Joiner.on(", ").join(keys));

            return node.getSource().accept(this, context);
        }

        @Override
        public Void visitOutput(OutputPlan node, Void context)
        {
            printNode(node, format("Output[%s]", Joiner.on(", ").join(node.getColumnNames())));

            return node.getSource().accept(this, context);
        }

        @Override
        public Void visitLimit(LimitNode node, Void context)
        {
            printNode(node, format("Limit[%s]", node.getCount()));

            return node.getSource().accept(this, context);
        }

        @Override
        public Void visitTableScan(TableScan node, Void context)
        {
            StringBuilder builder = new StringBuilder();
            for (Map.Entry<Symbol, ColumnHandle> entry : node.getAssignments().entrySet()) {
                builder.append(format("%s := %s\\n", entry.getValue(), entry.getKey()));
            }

            printNode(node, format("TableScan[%s]", node.getTable()), builder.toString());

            return null;
        }

        @Override
        public Void visitJoin(JoinNode node, Void context)
        {
            String criteria = ExpressionFormatter.toString(node.getCriteria());
            criteria = criteria.replace(">", "\\>");
            criteria = criteria.replace("<", "\\<");

            printNode(node, "Join", criteria);

            node.getLeft().accept(this, context);
            node.getRight().accept(this, context);

            return null;
        }

        private void printNode(PlanNode node, String label)
        {
            String nodeId = getNodeId(node);

            output.append(nodeId)
                    .append(format("[label=\"{%s|%s}\", shape=record]", label, node.getOutputSymbols()))
                    .append(';')
                    .append('\n');
        }

        private void printNode(PlanNode node, String label, String details)
        {
            String nodeId = getNodeId(node);

            output.append(nodeId)
                    .append(format("[label=\"{%s|%s|%s}\", shape=record]", label, node.getOutputSymbols(), details))
                    .append(';')
                    .append('\n');
        }
    }

    private static class EdgePrinter
            extends PlanVisitor<Void, Void>
    {
        private final StringBuilder output;
        private final Map<Integer, PlanFragment> fragmentsById;

        public EdgePrinter(StringBuilder output, Map<Integer, PlanFragment> fragmentsById)
        {
            this.output = output;
            this.fragmentsById = fragmentsById;
        }

        @Override
        protected Void visitPlan(PlanNode node, Void context)
        {
            for (PlanNode child : node.getSources()) {
                printEdge(node, child);

                child.accept(this, context);
            }

            return null;
        }

        @Override
        public Void visitExchange(ExchangeNode node, Void context)
        {
            PlanFragment target = fragmentsById.get(node.getSourceFragmentId());
            printEdge(node, target.getRoot());

            return null;
        }

        private void printEdge(PlanNode from, PlanNode to)
        {
            String fromId = getNodeId(from);
            String toId = getNodeId(to);

            output.append(fromId)
                    .append(" -> ")
                    .append(toId)
                    .append(';')
                    .append('\n');
        }
    }

    private static String getNodeId(PlanNode from)
    {
        return format("plannode_%s", System.identityHashCode(from));
    }
}