/*
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

import React from "react";
import {createRoot} from 'react-dom/client';
import ReactDOMServer from "react-dom/server";
import * as dagreD3 from "dagre-d3-es";
import * as d3 from "d3";
import {clsx} from 'clsx';

import {formatCount, formatDataSize, formatDuration, getFirstParameter, getTaskNumber, isQueryEnded, parseDuration} from "../utils";
import {initializeGraph, initializeSvg} from "../d3utils";
import {QueryHeader} from "./QueryHeader";

function getTotalWallTime(operator) {
    return parseDuration(operator.addInputWall) + parseDuration(operator.getOutputWall) + parseDuration(operator.finishWall) + parseDuration(operator.blockedWall)
}

class OperatorSummary extends React.Component {
    render() {
        const operator = this.props.operator;

        const totalWallTime = parseDuration(operator.addInputWall) + parseDuration(operator.getOutputWall) + parseDuration(operator.finishWall) + parseDuration(operator.blockedWall);

        const rowInputRate = totalWallTime === 0 ? 0 : (1.0 * operator.inputPositions) / (totalWallTime / 1000.0);
        const byteInputRate = totalWallTime === 0 ? 0 : (1.0 * operator.inputDataSizeInBytes) / (totalWallTime / 1000.0);

        return (
            <div className="header-data">
                <div className="highlight-row">
                    <div className="header-row">
                        {operator.operatorType}
                    </div>
                    <div>
                        {formatCount(rowInputRate) + " rows/s (" + formatDataSize(byteInputRate) + "/s)"}
                    </div>
                </div>
                <table className="table">
                    <tbody>
                    <tr>
                        <td>
                            Output
                        </td>
                        <td>
                            {formatCount(operator.outputPositions) + " rows (" + operator.outputDataSizeInBytes + ")"}
                        </td>
                    </tr>
                    <tr>
                        <td>
                            Drivers
                        </td>
                        <td>
                            {operator.totalDrivers}
                        </td>
                    </tr>
                    <tr>
                        <td>
                            Wall Time
                        </td>
                        <td>
                            {formatDuration(totalWallTime)}
                        </td>
                    </tr>
                    <tr>
                        <td>
                            Blocked
                        </td>
                        <td>
                            {formatDuration(parseDuration(operator.blockedWall))}
                        </td>
                    </tr>
                    <tr>
                        <td>
                            Input
                        </td>
                        <td>
                            {formatCount(operator.inputPositions) + " rows (" + operator.inputDataSizeInBytes + ")"}
                        </td>
                    </tr>
                    </tbody>
                </table>
            </div>
        );
    }
}

const BAR_CHART_PROPERTIES = {
    type: 'bar',
    barSpacing: '0',
    height: '80px',
    barColor: '#747F96',
    zeroColor: '#8997B3',
    chartRangeMin: 0,
    tooltipClassname: 'sparkline-tooltip',
    tooltipFormat: 'Task {{offset:offset}} - {{value}}',
    disableHiddenCheck: true,
};

function OperatorStatistic({id, name, operators, supplier, renderer}) {

    React.useEffect(() => {
        const statistic = operators.map(supplier);
        const numTasks = operators.length;

        const tooltipValueLookups = {'offset': {}};
        for (let i = 0; i < numTasks; i++) {
            tooltipValueLookups['offset'][i] = "" + i;
        }

        const stageBarChartProperties = $.extend({}, BAR_CHART_PROPERTIES, {barWidth: 800 / numTasks, tooltipValueLookups: tooltipValueLookups});
        $('#operator-statics-' + id).sparkline(statistic, $.extend({}, stageBarChartProperties, {numberFormatter: renderer}));

    }, [operators, supplier, renderer]);

    return (
        <div className="row operator-statistic">
            <div className="col-2 italic-uppercase operator-statistic-title">
                {name}
            </div>
            <div className="col-10">
                <span className="bar-chart" id={`operator-statics-${id}`}/>
            </div>
        </div>
    );
}

function OperatorDetail({index, operator, tasks}) {
    const selectedStatistics = [
        {
            name: "Total Wall Time",
            id: "totalWallTime",
            supplier: getTotalWallTime,
            renderer: formatDuration
        },
        {
            name: "Input Rows",
            id: "inputPositions",
            supplier: operator => operator.inputPositions,
            renderer: formatCount
        },
        {
            name: "Input Data Size",
            id: "inputDataSize",
            supplier: operator => operator.inputDataSizeInBytes,
            renderer: formatDataSize
        },
        {
            name: "Output Rows",
            id: "outputPositions",
            supplier: operator => operator.outputPositions,
            renderer: formatCount
        },
        {
            name: "Output Data Size",
            id: "outputDataSize",
            supplier: operator => operator.outputDataSizeInBytes,
            renderer: formatDataSize
        },
    ];

    const getOperatorTasks = () => {
        // sort the x-axis
        const tasksSorted = tasks.sort(function (taskA, taskB) {
            return getTaskNumber(taskA.taskId) - getTaskNumber(taskB.taskId);
        });

        const operatorTasks = [];
        tasksSorted.forEach(task => {
            task.stats.pipelines.forEach(pipeline => {
                if (pipeline.pipelineId === operator.pipelineId) {
                    pipeline.operatorSummaries.forEach(operator => {
                        if (operator.operatorId === operator.operatorId) {
                            operatorTasks.push(operator);
                        }
                    });
                }
            });
        });

        return operatorTasks;
    }

    const operatorTasks = getOperatorTasks();
    const totalWallTime = getTotalWallTime(operator);

    const rowInputRate = totalWallTime === 0 ? 0 : (1.0 * operator.inputPositions) / totalWallTime;
    const byteInputRate = totalWallTime === 0 ? 0 : (1.0 * operator.inputDataSizeInBytes) / (totalWallTime / 1000.0);

    const rowOutputRate = totalWallTime === 0 ? 0 : (1.0 * operator.outputPositions) / totalWallTime;
    const byteOutputRate = totalWallTime === 0 ? 0 : (1.0 * operator.outputDataSizeInBytes) / (totalWallTime / 1000.0);

    return (
        <div className="col-12 container mx-2">
            <div className="modal-header">
                <h3 className="modal-title fs-5">
                    <small>Pipeline {operator.pipelineId}</small>
                    <br/>
                    {operator.operatorType}
                </h3>
                <button type="button" className="btn-close" data-bs-dismiss="modal" aria-label="Close"></button>
            </div>
            <div className="row modal-body">
                <div className="col-12">
                    <div className="row">
                        <div className="col-6">
                            <table className="table">
                                <tbody>
                                <tr>
                                    <td>
                                        Input
                                    </td>
                                    <td>
                                        {formatCount(operator.inputPositions) + " rows (" + operator.inputDataSizeInBytes + ")"}
                                    </td>
                                </tr>
                                <tr>
                                    <td>
                                        Input Rate
                                    </td>
                                    <td>
                                        {formatCount(rowInputRate) + " rows/s (" + formatDataSize(byteInputRate) + "/s)"}
                                    </td>
                                </tr>
                                <tr>
                                    <td>
                                        Output
                                    </td>
                                    <td>
                                        {formatCount(operator.outputPositions) + " rows (" + operator.outputDataSizeInBytes + ")"}
                                    </td>
                                </tr>
                                <tr>
                                    <td>
                                        Output Rate
                                    </td>
                                    <td>
                                        {formatCount(rowOutputRate) + " rows/s (" + formatDataSize(byteOutputRate) + "/s)"}
                                    </td>
                                </tr>
                                </tbody>
                            </table>
                        </div>
                        <div className="col-6">
                            <table className="table">
                                <tbody>
                                <tr>
                                    <td>
                                        Wall Time
                                    </td>
                                    <td>
                                        {formatDuration(totalWallTime)}
                                    </td>
                                </tr>
                                <tr>
                                    <td>
                                        Blocked
                                    </td>
                                    <td>
                                        {formatDuration(parseDuration(operator.blockedWall))}
                                    </td>
                                </tr>
                                <tr>
                                    <td>
                                        Drivers
                                    </td>
                                    <td>
                                        {operator.totalDrivers}
                                    </td>
                                </tr>
                                <tr>
                                    <td>
                                        Tasks
                                    </td>
                                    <td>
                                        {operatorTasks.length}
                                    </td>
                                </tr>
                                </tbody>
                            </table>
                        </div>
                    </div>
                    <div className="row font-white">
                        <div className="col-2 italic-uppercase">
                            <strong>
                                Statistic
                            </strong>
                        </div>
                        <div className="col-10 italic-uppercase">
                            <strong>
                                Tasks
                            </strong>
                        </div>
                    </div>
                    {
                        selectedStatistics.map(function (statistic) {
                            return (
                                <OperatorStatistic
                                    key={statistic.id}
                                    id={statistic.id}
                                    name={statistic.name}
                                    supplier={statistic.supplier}
                                    renderer={statistic.renderer}
                                    operators={operatorTasks}/>
                            );
                        })
                    }
                    <p/>
                    <p/>
                </div>
            </div>
        </div>
    );
}

class StageOperatorGraph extends React.Component {
    componentDidMount() {
        this.updateD3Graph();
    }

    componentDidUpdate() {
        this.updateD3Graph();
    }

    handleOperatorClick(event) {
        if (event.target.hasOwnProperty("__data__") && event.target.__data__ !== undefined) {
            $('#operator-detail-modal').modal("show")

            const pipelineId = (event?.target?.__data__ || "").split('-').length > 0 ? parseInt((event?.target?.__data__ || '').split('-')[1] || '0') : 0;
            const operatorId = (event?.target?.__data__ || "").split('-').length > 0 ? parseInt((event?.target?.__data__ || '').split('-')[2] || '0') : 0;
            const stage = this.props.stage;

            let operatorStageSummary = null;
            const operatorSummaries = stage.latestAttemptExecutionInfo.stats.operatorSummaries;
            for (let i = 0; i < operatorSummaries.length; i++) {
                if (operatorSummaries[i].pipelineId === pipelineId && operatorSummaries[i].operatorId === operatorId) {
                    operatorStageSummary = operatorSummaries[i];
                }
            }
            const container = document.getElementById('operator-detail');
            const root = createRoot(container);
            root.render(<OperatorDetail key={event} operator={operatorStageSummary} tasks={stage.latestAttemptExecutionInfo.tasks}/>);
        }
        else {
            return;
        }
    }

    computeOperatorGraphs() {
        const pipelineOperators = new Map();
        this.props.stage.latestAttemptExecutionInfo.stats.operatorSummaries.forEach(operator => {
            if (!pipelineOperators.has(operator.pipelineId)) {
                pipelineOperators.set(operator.pipelineId, []);
            }
            pipelineOperators.get(operator.pipelineId).push(operator);
        });

        const result = new Map();
        pipelineOperators.forEach((pipelineOperators, pipelineId) => {
            // sort deep-copied operators in this pipeline from source to sink
            const linkedOperators = pipelineOperators.map(a => Object.assign({}, a)).sort((a, b) => a.operatorId - b.operatorId);
            const sinkOperator = linkedOperators[linkedOperators.length - 1];
            const sourceOperator = linkedOperators[0];

            // chain operators at this level
            let currentOperator = sourceOperator;
            linkedOperators.slice(1).forEach(source => {
                source.child = currentOperator;
                currentOperator = source;
            });

            result.set(pipelineId, sinkOperator);
        });

        return result;
    }

    computeD3StageOperatorGraph(graph, operator, sink, pipelineNode) {
        const operatorNodeId = "operator-" + operator.pipelineId + "-" + operator.operatorId;

        // this is a non-standard use of ReactDOMServer, but it's the cleanest way to unify DagreD3 with React
        const html = ReactDOMServer.renderToString(<OperatorSummary key={operator.pipelineId + "-" + operator.operatorId} operator={operator}/>);
        graph.setNode(operatorNodeId, {class: "operator-stats", label: html, labelType: "html"});

        if (operator.hasOwnProperty("child")) {
            this.computeD3StageOperatorGraph(graph, operator.child, operatorNodeId, pipelineNode);
        }

        if (sink !== null) {
            graph.setEdge(operatorNodeId, sink, {class: "plan-edge", arrowheadClass: "plan-arrowhead"});
        }

        graph.setParent(operatorNodeId, pipelineNode);
    }

    updateD3Graph() {
        if (!this.props.stage) {
            return;
        }

        const operatorGraphs = this.computeOperatorGraphs();

        const graph = initializeGraph();
        operatorGraphs.forEach((operator, pipelineId) => {
            const pipelineNodeId = "pipeline-" + pipelineId;
            graph.setNode(pipelineNodeId, {label: "Pipeline " + pipelineId + " ", clusterLabelPos: 'top', style: 'fill: #2b2b2b', labelStyle: 'fill: #fff'});
            this.computeD3StageOperatorGraph(graph, operator, null, pipelineNodeId)
        });

        $("#operator-canvas").html("");

        if (operatorGraphs.size > 0) {
            $(".graph-container").css("display", "block");
            const svg = initializeSvg("#operator-canvas");
            const render = new dagreD3.render();
            render(d3.select("#operator-canvas g"), graph);

            svg.selectAll("g.operator-stats").on("click", this.handleOperatorClick.bind(this));
            svg.attr("height", graph.graph().height);
            svg.attr("width", graph.graph().width);
        }
        else {
            $(".graph-container").css("display", "none");
        }
    }

    render() {
        const stage = this.props.stage;

        if (!stage.hasOwnProperty('plan')) {
            return (
                <div className="row error-message">
                    <div className="col-12"><h4>Stage does not have a plan</h4></div>
                </div>
            );
        }

        const latestAttemptExecutionInfo = stage.latestAttemptExecutionInfo;
        if (!latestAttemptExecutionInfo.hasOwnProperty('stats') || !latestAttemptExecutionInfo.stats.hasOwnProperty("operatorSummaries") || latestAttemptExecutionInfo.stats.operatorSummaries.length === 0) {
            return (
                <div className="row error-message">
                    <div className="col-12">
                        <h4>Operator data not available for {stage.stageId}</h4>
                    </div>
                </div>
            );
        }

        return null;
    }
}

export class StageDetail extends React.Component {
    constructor(props) {
        super(props);
        this.state = {
            initialized: false,
            ended: false,

            selectedStageId: null,
            query: null,

            lastRefresh: null,
            lastRender: null
        };

        this.refreshLoop = this.refreshLoop.bind(this);
    }

    resetTimer() {
        clearTimeout(this.timeoutId);
        // stop refreshing when query finishes or fails
        if (this.state.query === null || !this.state.ended) {
            this.timeoutId = setTimeout(this.refreshLoop, 1000);
        }
    }

    static getQueryURL(id) {
        if (!id || typeof id !== 'string' || id.length === 0) {
            return "/v1/query/undefined";
        }
        const sanitizedId = id.replace(/[^a-z0-9_]/gi, '');
        return sanitizedId.length > 0 ? `/v1/query/${encodeURIComponent(sanitizedId)}` : "/v1/query/undefined";
    }

    refreshLoop() {
        clearTimeout(this.timeoutId); // to stop multiple series of refreshLoop from going on simultaneously
        const queryString = getFirstParameter(window.location.search).split('.');
        const rawQueryId = queryString.length > 0 ? queryString[0] : "";
        let selectedStageId = this.state.selectedStageId;
        if (selectedStageId === null) {
            selectedStageId = 0;
            if (queryString.length > 1) {
                selectedStageId = parseInt(queryString[1]);
            }
        }


        $.get(StageDetail.getQueryURL(rawQueryId), query => {
            this.setState({
                initialized: true,
                ended: query.finalQueryInfo,

                selectedStageId: selectedStageId,
                query: query,
            });
            this.resetTimer();
        })
    }

    componentDidMount() {
        this.refreshLoop();
    }

    findStage(stageId, currentStage) {
        if (stageId === null) {
            return null;
        }

        if (currentStage.stageId === stageId) {
            return currentStage;
        }

        for (let i = 0; i < currentStage.subStages.length; i++) {
            const stage = this.findStage(stageId, currentStage.subStages[i]);
            if (stage !== null) {
                return stage;
            }
        }

        return null;
    }

    getAllStageIds(result, currentStage) {
        result.push(currentStage.plan.id);
        currentStage.subStages.forEach(stage => {
            this.getAllStageIds(result, stage);
        });
    }

    render() {
        if (!this.state.query) {
            let label = (<div className="loader">Loading...</div>);
            if (this.state.initialized) {
                label = "Query not found";
            }
            return (
                <div className="row error-message">
                    <div className="col-12"><h4>{label}</h4></div>
                </div>
            );
        }

        if (!this.state.query.outputStage) {
            return (
                <div className="row error-message">
                    <div className="col-12 res-heading"><h4>Query does not have an output stage</h4></div>
                </div>
            );
        }

        const query = this.state.query;
        const allStages = [];
        this.getAllStageIds(allStages, query.outputStage);

        const stage = this.findStage(query.queryId + "." + this.state.selectedStageId, query.outputStage);
        if (stage === null) {
            return (
                <div className="row error-message">
                    <div className="col-12"><h4>Stage not found</h4></div>
                </div>
            );
        }

        let stageOperatorGraph = null;
        if (!isQueryEnded(query.state)) {
            stageOperatorGraph = (
                <div className="row error-message">
                    <div className="col-12">
                        <h4>Operator graph will appear automatically when query completes.</h4>
                        <div className="loader">Loading...</div>
                    </div>
                </div>
            )
        }
        else {
            stageOperatorGraph = <StageOperatorGraph id={stage.stageId} stage={stage}/>;
        }

        return (
            <div>
                <QueryHeader query={query}/>
                <div className="row">
                    <div className="col-12">
                        <div className="row justify-content-between">
                            <div className="col-2 align-self-end">
                                <h3>Stage {stage.plan.id}</h3>
                            </div>
                            <div className="col-2 align-self-end">
                                <div className="stage-dropdown" role="group">
                                    <div className="btn-group">
                                        <button type="button" className="btn bg-white btn-secondary text-dark dropdown-toggle"
                                                data-bs-toggle="dropdown" aria-haspopup="true"
                                                aria-expanded="false">Select Stage<span className="caret"/>
                                        </button>
                                        <ul className="dropdown-menu bg-white">
                                            {
                                                allStages.map(stageId => (
                                                    <li key={stageId}>
                                                        <a className={clsx('dropdown-item text-dark', stage.plan.id === stageId && 'selected')}
                                                           onClick={() => this.setState({selectedStageId: stageId})}>{stageId}</a>
                                                    </li>
                                                ))
                                            }
                                        </ul>
                                    </div>
                                </div>
                            </div>
                        </div>
                    </div>
                </div>
                <hr className="h3-hr"/>
                <div className="row">
                    <div className="col-12">
                        {stageOperatorGraph}
                    </div>
                </div>
            </div>
        );
    }
}

export default StageDetail;