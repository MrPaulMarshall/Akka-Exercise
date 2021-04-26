import akka.actor.typed.ActorRef;

import java.util.List;
import java.util.Map;

/**
 * Interface for messages
 */
public class MessageTypes {

    /**
     * Interface
     */
    interface Message {}

    /**
     * Message from Main to MonitoringStation
     */
    public static class UserQueryRequest implements Message {
        final int firstSatID;
        final int range;
        final int timeout;

        public UserQueryRequest(int firstSatID, int range, int timeout) {
            this.firstSatID = firstSatID;
            this.range = range;
            this.timeout = timeout;
        }
    }

    /**
     * Query from station to dispatcher
     */
    public static class StatusQuery implements Message {
        final ActorRef<Message> actorRef;
        final int monitoringStationID;
        final int queryID;
        final int firstSatelliteID;
        final int range;
        final int timeout;

        public StatusQuery(ActorRef<Message> actorRef, int monitoringStationID, int queryID, int firstSatelliteID, int range, int timeout) {
            this.actorRef = actorRef;
            this.monitoringStationID = monitoringStationID;
            this.queryID = queryID;
            this.firstSatelliteID = firstSatelliteID;
            this.range = range;
            this.timeout = timeout;
        }
    }

    /**
     * Response from dispatcher to station
     */
    public static class StatusResponse implements Message {
        final int queryID;
        final Map<Integer, SatelliteAPI.Status> errorsMap;
        final double receivedResponsesPercentage;

        public StatusResponse(int queryID, Map<Integer, SatelliteAPI.Status> errorsMap, double receivedResponsesPercentage) {
            this.queryID = queryID;
            this.errorsMap = errorsMap;
            this.receivedResponsesPercentage = receivedResponsesPercentage;
        }
    }

    /**
     * Reminder to stop waiting for responses from workers and send results to monitoring station
     */
    public static class SelfTimeoutReminder implements Message {
        final ActorDispatcher.StationQueryPair requestIDPair;
        final int satelliteID;

        public SelfTimeoutReminder(ActorDispatcher.StationQueryPair requestIDPair, int satelliteID) {
            this.requestIDPair = requestIDPair;
            this.satelliteID = satelliteID;
        }
    }

    /**
     * New logs to write in the database
     */
    public static class LogsToSave implements Message {
        final List<Integer> stationsThatReturnedError;

        public LogsToSave(List<Integer> stationsThatReturnedError) {
            this.stationsThatReturnedError = stationsThatReturnedError;
        }
    }

    /**
     * Query for the satellite's saved errors
     */
    public static class SatelliteErrorsQuery implements Message {
        final ActorRef<Message> actorRef;
        final int satelliteID;

        public SatelliteErrorsQuery(ActorRef<Message> actorRef, int satelliteID) {
            this.actorRef = actorRef;
            this.satelliteID = satelliteID;
        }
    }

    /**
     * Response for the satellite's errors query
     */
    public static class SatelliteErrorsResponse implements Message {
        final int satelliteID;
        final int errorsCount;

        public SatelliteErrorsResponse(int satelliteID, int errorsCount) {
            this.satelliteID = satelliteID;
            this.errorsCount = errorsCount;
        }
    }

    /**
     * Shutdown order
     */
    public static class ShutdownOrder implements Message {
    }
}
