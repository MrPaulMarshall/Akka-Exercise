import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.DispatcherSelector;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class ActorDispatcher extends AbstractBehavior<MessageTypes.Message> {

    // =======================
    // Fields & constructor
    // =======================

    /**
     * Fields of Dispatcher actor
     */
    private final Executor executor;
    private final Map<StationQueryPair, ActiveQueryState> activeRequests = new HashMap<>();
    private final ActorRef<MessageTypes.Message> databaseConnectionRef;

    /**
     * Constructor
     */
    public ActorDispatcher(ActorContext<MessageTypes.Message> context, ActorRef<MessageTypes.Message> databaseConnectionRef) {
        super(context);
        this.databaseConnectionRef = databaseConnectionRef;

        this.executor = context
                .getSystem()
                .dispatchers()
                .lookup(DispatcherSelector.fromConfig("my-dispatcher"));
    }

    /**
     * ActorDispatcher::create
     */
    public static Behavior<MessageTypes.Message> create(ActorRef<MessageTypes.Message> databaseConnectionRef) {
        return Behaviors.setup(context -> new ActorDispatcher(context, databaseConnectionRef));
    }

    // =======================
    // Message handlers
    // =======================

    /**
     * Public handler - this::createReceive
     */
    @Override
    public Receive<MessageTypes.Message> createReceive() {
        return newReceiveBuilder()
                .onMessage(MessageTypes.StatusQuery.class, this::onRequest)
                .onMessage(MessageTypes.SelfTimeoutReminder.class, this::onSelfReminder)
                .build();
    }

    /**
     * Internal handler for request from MonitStation
     * Starts 'range' new Threads to ask SatelliteAPI for statuses
     */
    private Behavior<MessageTypes.Message> onRequest(MessageTypes.StatusQuery msg) {
        StationQueryPair queryKey = new StationQueryPair(msg.monitoringStationID, msg.queryID);
        if (this.activeRequests.containsKey(queryKey)) return this;
        ActiveQueryState state = new ActiveQueryState(msg.actorRef, msg.firstSatelliteID, msg.range, msg.timeout);
        this.activeRequests.put(queryKey, state);

        for (int i = 0; i < msg.range; i++) {
            final int satelliteID = msg.firstSatelliteID + i;

            CompletableFuture<SatelliteAPI.Status> future = CompletableFuture
                    .supplyAsync(() -> SatelliteAPI.getStatus(satelliteID), this.executor);

            state.activeQuestions.put(satelliteID, future);
            getContext().getSystem().scheduler().scheduleOnce(
                    Duration.ofMillis(msg.timeout),
                    () -> getContext().getSelf().tell(new MessageTypes.SelfTimeoutReminder(queryKey, satelliteID)),
                    getContext().getExecutionContext());
        }

        return this;
    }

    /**
     * Internal handler for self reminder to close next thread
     */
    private Behavior<MessageTypes.Message> onSelfReminder(MessageTypes.SelfTimeoutReminder msg) {
        if (!this.activeRequests.containsKey(msg.requestIDPair)) return this;
        ActiveQueryState state = this.activeRequests.get(msg.requestIDPair);

        Future<SatelliteAPI.Status> future = state.activeQuestions.get(msg.satelliteID);
        if (future.isDone()) {
            try {
                SatelliteAPI.Status status = future.get();
                state.receivedResponses++;
                if (status != SatelliteAPI.Status.OK) {
                    state.errorsMap.put(msg.satelliteID, status);
                }
            } catch (InterruptedException | ExecutionException ignored) {}
        }
        else {
            future.cancel(true);
        }
        state.checkedThreads++;
        state.activeQuestions.remove(msg.satelliteID);

        // if all threads have been checked remove this query and send results to MonitStation
        if (state.checkedThreads == state.range) {
            state.stationRef.tell(new MessageTypes.StatusResponse(
                    msg.requestIDPair.queryID,
                    state.errorsMap,
                    (double) state.receivedResponses / state.range));
            this.activeRequests.remove(msg.requestIDPair);

            // save results to database
            databaseConnectionRef.tell(new MessageTypes.LogsToSave(
                    state.errorsMap.keySet().stream().sorted().collect(Collectors.toList())));
        }
        return this;
    }

    // =======================
    // Internal types
    // =======================

    /**
     * Pair < stationID, queryID > used to identify specific query
     */
    static class StationQueryPair {
        final int stationID;
        final int queryID;

        private StationQueryPair(int stationID, int queryID) {
            this.stationID = stationID;
            this.queryID = queryID;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null) return false;
            if (o instanceof StationQueryPair) {
                StationQueryPair other = (StationQueryPair) o;
                return this.stationID == other.stationID && this.queryID == other.queryID;
            }
            return false;
        }

        @Override
        public int hashCode() {
            return this.stationID + 2137 * this.queryID;
        }
    }

    /**
     * Stores state of query that is currently executed by dispatcher
     */
    static class ActiveQueryState {
        final ActorRef<MessageTypes.Message> stationRef;
        final int firstSatID;
        final int range;
        final int timeout;

        final Map<Integer, SatelliteAPI.Status> errorsMap;
        final Map<Integer, Future<SatelliteAPI.Status>> activeQuestions;
        int checkedThreads;
        int receivedResponses;

        ActiveQueryState(ActorRef<MessageTypes.Message> stationRef, int firstSatID, int range, int timeout) {
            this.stationRef = stationRef;
            this.firstSatID = firstSatID;
            this.range = range;
            this.timeout = timeout;
            this.errorsMap = new HashMap<>();
            this.activeQuestions = new HashMap<>();
            this.checkedThreads = 0;
            this.receivedResponses = 0;
        }
    }
}
