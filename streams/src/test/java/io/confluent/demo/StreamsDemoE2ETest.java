package io.confluent.demo;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.LongDeserializer;
import org.apache.kafka.common.serialization.LongSerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.TopologyTestDriver;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.test.ConsumerRecordFactory;
import org.apache.kafka.streams.test.OutputVerifier;
import org.junit.Before;
import org.junit.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;

import io.confluent.demo.fixture.MoviesAndRatingsData;
import io.confluent.kafka.schemaregistry.client.MockSchemaRegistryClient;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import lombok.extern.slf4j.Slf4j;

import static io.confluent.demo.StreamsDemo.*;
import static io.confluent.demo.fixture.MoviesAndRatingsData.DUMMY_KAFKA_CONFLUENT_CLOUD_9092;
import static io.confluent.demo.fixture.MoviesAndRatingsData.DUMMY_SR_CONFLUENT_CLOUD_8080;
import static io.confluent.demo.fixture.MoviesAndRatingsData.LETHAL_WEAPON_MOVIE;
import static io.confluent.demo.fixture.MoviesAndRatingsData.LETHAL_WEAPON_RATING_9;
import static io.confluent.kafka.serializers.AbstractKafkaAvroSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG;
import static java.util.Collections.singletonMap;

@Slf4j
public class StreamsDemoE2ETest {

  TopologyTestDriver td;

  @Before
  public void setUp() {

    final Properties config = getStreamsConfig(DUMMY_KAFKA_CONFLUENT_CLOUD_9092, DUMMY_SR_CONFLUENT_CLOUD_8080);
    // workaround https://stackoverflow.com/a/50933452/27563
    config.setProperty(StreamsConfig.STATE_DIR_CONFIG, "/tmp/kafka-streams/" + LocalDateTime.now().toString());

    //building topology
    StreamsBuilder builder = new StreamsBuilder();

    SpecificAvroSerde<Movie> movieSerde = new SpecificAvroSerde<>(new MockSchemaRegistryClient());
    movieSerde.configure(singletonMap(SCHEMA_REGISTRY_URL_CONFIG, DUMMY_SR_CONFLUENT_CLOUD_8080), false);
    final KTable<Long, Movie> moviesTable = getMoviesTable(builder, movieSerde);
    final KStream<Long, String> rawRatingsStream = getRawRatingsStream(builder);
    final KTable<Long, Double> ratingAverageTable = getRatingAverageTable(rawRatingsStream);
    final KTable<Long, String> ratedMoviesTable = getRatedMoviesTable(moviesTable, ratingAverageTable);

    final Topology topology = builder.build();
    log.info("Topology = {}\n", topology);
    td = new TopologyTestDriver(topology, config);
  }

  @Test
  public void validateRatingForLethalWeapon() {

    ConsumerRecordFactory<Long, String> rawRatingsRecordFactory =
        new ConsumerRecordFactory<>(RAW_RATINGS_TOPIC_NAME, new LongSerializer(), new StringSerializer());

    ConsumerRecordFactory<Long, String> rawMoviesRecordFactory =
        new ConsumerRecordFactory<>(RAW_MOVIES_TOPIC_NAME, new LongSerializer(), new StringSerializer());

    td.pipeInput(rawMoviesRecordFactory.create(LETHAL_WEAPON_MOVIE));

    List<ConsumerRecord<byte[], byte[]>> list = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      list.add(rawRatingsRecordFactory.create(LETHAL_WEAPON_RATING_9));
    }
    td.pipeInput(list);

    List<ProducerRecord<Long, String>> result = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      result.add(td.readOutput("rated-movies", new LongDeserializer(), new StringDeserializer()));
    }
    result.forEach(record -> {
      OutputVerifier.compareValue(record, "Lethal Weapon=9.0");
    });


  }

}
