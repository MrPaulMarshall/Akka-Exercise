import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.Terminated;
import akka.actor.typed.javadsl.Behaviors;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import java.io.File;
import java.util.LinkedList;
import java.util.Random;

public class Main {

    private static final String pathToDatabase = "src/main/resources/database.db";
    private static final Random random = new Random();

    public static Behavior<Void> create() {
        return Behaviors.setup(
                context -> {
                    // create database connection
                    ActorRef<MessageTypes.Message> databaseConnection = context.spawn(
                            ActorDatabaseConnection.create("jdbc:sqlite:" + pathToDatabase), "database");

                    // create dispatcher
                    ActorRef<MessageTypes.Message> dispatcher = context.spawn(
                            ActorDispatcher.create(databaseConnection), "dispatcher");

                    // create monit stations
                    LinkedList<ActorRef<MessageTypes.Message>> monitStations = new LinkedList<>();
                    for (int id = 0; id < 3; id++) {
                        monitStations.add(context.spawn(
                                ActorMonitStation.create(id, dispatcher, databaseConnection), "station" + id));
                    }

                    // send requests
                    for (ActorRef<MessageTypes.Message> station: monitStations) {
                        station.tell(new MessageTypes.UserQueryRequest(
                                100 + random.nextInt(50), 50, 300));
                        station.tell(new MessageTypes.UserQueryRequest(
                                100 + random.nextInt(50), 50, 300));
                    }

                    // send requests to database
                    Thread.sleep(1000);
                    for (int id = 100; id < 200; id++) {
                        monitStations.getFirst().tell(new MessageTypes.SatelliteErrorsQuery(null, id));
                    }

                    return Behaviors.receive(Void.class)
                            .onSignal(Terminated.class, sig -> Behaviors.stopped())
                            .build();
                });
    }

    public static void main(String[] args) {
        File configFile = new File("src/main/resources/dispatcher.conf");
        Config config = ConfigFactory.parseFile(configFile);
        ActorSystem.create(Main.create(), "main", config);
    }
}
