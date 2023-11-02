#!/bin/bash

HYPERFOIL_HOME=./hyperfoil

DURATION=40

EVENT=cpu

# this can be html or jfr
FORMAT=html

JFR=false

THREADS=1

CONNECTIONS=100

JFR_ARGS=

PERF=false

Help()
{
   # Display Help
   echo "Syntax: benchmark [OPTIONS]"
   echo "options:"
   echo "h    Display this guide."
   echo ""
   echo "e    event to profile, if supported e.g. -e cpu "
   echo "     check https://github.com/jvm-profiling-tools/async-profiler#profiler-options for the complete list"
   echo "     default is cpu"
   echo ""
   echo "f    output format, if supported by the profiler. e.g. async-profiler support html,jfr,collapsed"
   echo "     default is html"
   echo ""
   echo "d    duration of the load generation phase, in seconds"
   echo "     default is 20"
   echo ""
   echo "j    if specified, it uses JFR profiling. async-profiler otherwise."
   echo ""
   echo "t    number of I/O threads of the server application."
   echo ""
   echo "     default is 1"
   echo ""
   echo "c    number of connections used by the load generator."
   echo "     default is 100"
   echo ""
   echo "p    if specified, run perf stat together with the selected profiler. Only GNU Linux."
}

while getopts "he::f::d::jt::c:p" option; do
   case $option in
      h) Help
         exit;;
      e) EVENT=${OPTARG}
         ;;
      f) FORMAT=${OPTARG}
         ;;
      d) DURATION=${OPTARG}
         ;;
      j) JFR=true
         ;;
      t) THREADS=${OPTARG}
         ;;
      c) CONNECTIONS=${OPTARG}
         ;;
      p) PERF=true
         ;;
   esac
done

WARMUP=$((${DURATION}*2/5))

PROFILING=$((${DURATION}/2))

FULL_URL=http://localhost:8080/

echo "----- Benchmarking endpoint ${FULL_URL}"

# set sysctl kernel variables only if necessary
if [[ "$OSTYPE" == "linux-gnu" ]]; then
  current_value=$(sysctl -n kernel.perf_event_paranoid)
  if [ "$current_value" -ne 1 ]; then
    sudo sysctl kernel.perf_event_paranoid=1
    sudo sysctl kernel.kptr_restrict=0
  fi
fi

if [ "${JFR}" = true ]; then
   JFR_ARGS=-XX:+FlightRecorder
fi

trap 'echo "cleaning up server process";kill ${server_pid}' SIGINT SIGTERM SIGKILL

# -XX:+UnlockExperimentalVMOptions -XX:+DoJVMTIVirtualThreadTransition is required due to https://github.com/async-profiler/async-profiler/issues/779
java ${JFR_ARGS} -XX:+EnableDynamicAgentLoading -XX:+UnlockExperimentalVMOptions -XX:-DoJVMTIVirtualThreadTransitions -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints -DeventLoopThreads=${THREADS} -jar ../target/netty-http-jar-with-dependencies.jar &
server_pid=$!

sleep 2

echo "----- Server running at pid $server_pid using ${THREADS} I/O threads"

echo "----- Start all-out test and profiling"
${HYPERFOIL_HOME}/bin/wrk.sh -c ${CONNECTIONS} -t ${THREADS} -d ${DURATION}s ${FULL_URL} &

wrk_pid=$!

echo "----- Waiting $WARMUP seconds before profiling for $PROFILING seconds"

sleep $WARMUP

NOW=$(date "+%y%m%d_%H_%M_%S")

if [ "${JFR}" = true ]
then
  jcmd $server_pid JFR.start duration=${PROFILING}s filename=${NOW}.jfr dumponexit=true settings=profile
else
  echo "----- Starting async-profiler on ($server_pid)"
  java -jar ap-loader-all.jar profiler -e ${EVENT} -t -d ${PROFILING} -f ${NOW}_${EVENT}.${FORMAT} $server_pid &
fi

ap_pid=$!

if [ "${PERF}" = true ]; then
  echo "----- Collecting perf stat on $server_pid"
  perf stat -d -p $server_pid &
  stat_pid=$!
fi

echo "----- Showing stats for $WARMUP seconds"

if [[ "$OSTYPE" == "linux-gnu" ]]; then
  pidstat -p $server_pid 1 &
  pidstat_pid=$!
  sleep $WARMUP
  kill -SIGTERM $pidstat_pid
else
  # Print stats header
  ps -p $server_pid -o %cpu,rss,vsz | head -1
  sleep 1;
  # Print stats
  for (( i=1; i<$WARMUP; i++ )); do ps -p $server_pid -o %cpu,rss,vsz | tail -1;sleep 1;done;
fi

echo "----- Stopped stats, waiting load to complete"

wait $ap_pid

if [ "${PERF}" = true ]; then
  kill -SIGINT $stat_pid
fi

wait $wrk_pid

echo "----- Profiling and workload completed: killing server"

kill -SIGTERM $server_pid
