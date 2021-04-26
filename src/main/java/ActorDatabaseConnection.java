import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;

import java.sql.*;

public class ActorDatabaseConnection extends AbstractBehavior<MessageTypes.Message> {

    // =======================
    // Fields & constructor
    // =======================

    /**
     * Fields of DatabaseConnection actor
     */
    Connection connection = null;

    /**
     * Constructor
     */
    public ActorDatabaseConnection(ActorContext<MessageTypes.Message> context, String url) {
        super(context);

        try {
            // connect to the database
            this.connection = DriverManager.getConnection(url);

            // truncate table
            String truncateSql = "DELETE FROM errors;";
            PreparedStatement preparedTruncateStatement = this.connection.prepareStatement(truncateSql);
            preparedTruncateStatement.executeUpdate();

            // insert new rows with 0s
            this.connection.setAutoCommit(false);
            String insertSql = "INSERT INTO errors(sat_id, count_errors) VALUES(?, 0);";
            PreparedStatement preparedStatement = this.connection.prepareStatement(insertSql);
            for (int id = 100; id < 200; id++) {
                preparedStatement.setInt(1, id);
                preparedStatement.addBatch();
            }
            preparedStatement.executeBatch();
            this.connection.commit();

        } catch (SQLException e) {
            e.printStackTrace();
            getContext().getSelf().tell(new MessageTypes.ShutdownOrder());
        }
    }

    /**
     * ActorDispatcher::create
     */
    public static Behavior<MessageTypes.Message> create(String url) {
        return Behaviors.setup(context -> new ActorDatabaseConnection(context, url));
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
                .onMessage(MessageTypes.SatelliteErrorsQuery.class, this::onQuery)
                .onMessage(MessageTypes.LogsToSave.class, this::onLogsReceive)
                .onMessage(MessageTypes.ShutdownOrder.class, this::onShutdown)
                .build();
    }

    /**
     * Internal handler for query about errors of specific satellite
     */
    private Behavior<MessageTypes.Message> onQuery(MessageTypes.SatelliteErrorsQuery msg) {
        String query = "SELECT count_errors FROM errors WHERE sat_id = ?;";
        int countErrors = -1;
        try {
            PreparedStatement stmt = this.connection.prepareStatement(query);
            stmt.setInt(1, msg.satelliteID);
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                countErrors = rs.getInt(1);
            }
            else {
                throw new SQLException("Didn't found results for satellite with ID: " + msg.satelliteID);
            }

            msg.actorRef.tell(new MessageTypes.SatelliteErrorsResponse(msg.satelliteID, countErrors));
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return this;
    }

    /**
     * Internal handler for list of new logs to save
     */
    private Behavior<MessageTypes.Message> onLogsReceive(MessageTypes.LogsToSave msg) {
        String query = "UPDATE errors SET count_errors = count_errors + 1 WHERE sat_id = ?;";
        try {
            PreparedStatement stmt = this.connection.prepareStatement(query);
            for (int satelliteID: msg.stationsThatReturnedError) {
                stmt.setInt(1, satelliteID);
                stmt.addBatch();
            }
            stmt.executeBatch();
            this.connection.commit();
        } catch (SQLException e) {
            e.printStackTrace();
            try {
                this.connection.rollback();
            } catch (SQLException ignored) {}
        }

        return this;
    }

    /**
     * Internal handler for shutdown message
     */
    private Behavior<MessageTypes.Message> onShutdown(MessageTypes.ShutdownOrder msg) {
        if (this.connection != null) {
            try {
                this.connection.close();
            } catch (SQLException ignored) {}
        }
        return Behaviors.stopped();
    }
}
