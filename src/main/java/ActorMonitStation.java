import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class ActorMonitStation extends AbstractBehavior<MessageTypes.Message> {

    // =======================
    // Fields & constructor
    // =======================

    /**
     * Fields of MonitoringStation actor
     */
    private final ActorRef<MessageTypes.Message> dispatcher;
    private final ActorRef<MessageTypes.Message> databaseConnection;
    private final int stationID;
    private int nextRequestID;

    private final Map<Integer, Long> queriesTimestamps;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    /**
     * Constructor
     */
    public ActorMonitStation(ActorContext<MessageTypes.Message> context, ActorRef<MessageTypes.Message> dispatcher,
                             ActorRef<MessageTypes.Message> databaseConnection, int stationID) {
        super(context);
        this.dispatcher = dispatcher;
        this.databaseConnection = databaseConnection;
        this.stationID = stationID;
        this.nextRequestID = 0;
        this.queriesTimestamps = new HashMap<>();
    }

    /**
     * ActorDispatcher::create
     */
    public static Behavior<MessageTypes.Message> create(int ID, ActorRef<MessageTypes.Message> dispatcher,
                                                        ActorRef<MessageTypes.Message> databaseConnection) {
        return Behaviors.setup((context) -> new ActorMonitStation(context, dispatcher, databaseConnection, ID));
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
                .onMessage(MessageTypes.UserQueryRequest.class, this::onUserRequest)
                .onMessage(MessageTypes.StatusResponse.class, this::onResponse)
                .onMessage(MessageTypes.SatelliteErrorsQuery.class, this::onDatabaseQuery)
                .onMessage(MessageTypes.SatelliteErrorsResponse.class, this::onErrorsResponse)
                .build();
    }

    /**
     * Internal handler for user request - message from Main::main
     */
    private Behavior<MessageTypes.Message> onUserRequest(MessageTypes.UserQueryRequest msg) {
        dispatcher.tell(new MessageTypes.StatusQuery(getContext().getSelf(), this.stationID, this.nextRequestID,
                msg.firstSatID, msg.range, msg.timeout));
        this.queriesTimestamps.put(this.nextRequestID, System.currentTimeMillis());
        this.nextRequestID++;
        return this;
    }

    /**
     * Internal handler for response from Dispatcher
     */
    private Behavior<MessageTypes.Message> onResponse(MessageTypes.StatusResponse msg) {
        long elapsedTime = System.currentTimeMillis() - this.queriesTimestamps.get(msg.queryID);
        this.queriesTimestamps.remove(msg.queryID);

        executor.submit(() -> {
            List<Integer> sortedKeys = msg.errorsMap.keySet().stream().sorted().collect(Collectors.toList());
            synchronized (System.out) {
                System.out.println("Station name: " + getContext().getSelf().path().name());
                System.out.println("Elapsed time: " + elapsedTime + " ms");
                System.out.println("Answer ratio: " + String.format("%.0f", 100 * msg.receivedResponsesPercentage) + "%");
                System.out.println("Errors count: " + msg.errorsMap.size());

                for (int key: sortedKeys) {
                    System.out.println(key + ": " + msg.errorsMap.get(key));
                }
                System.out.println();
            }
        });

        return this;
    }

    /**
     * Internal handler for user request about database data
     */
    private Behavior<MessageTypes.Message> onDatabaseQuery(MessageTypes.SatelliteErrorsQuery msg) {
        databaseConnection.tell(new MessageTypes.SatelliteErrorsQuery(getContext().getSelf(), msg.satelliteID));
        return this;
    }

    /**
     * Internal handler for response from database connection actor
     */
    private Behavior<MessageTypes.Message> onErrorsResponse(MessageTypes.SatelliteErrorsResponse msg) {
        if (msg.errorsCount == 0) return this;
        synchronized (System.out) {
            System.out.println("Satellite " + msg.satelliteID + " reported " + msg.errorsCount + " errors");
        }
        return this;
    }
}
