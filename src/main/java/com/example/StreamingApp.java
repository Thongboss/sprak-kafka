package com.example;

import org.apache.spark.sql.*;
import org.apache.spark.sql.streaming.StreamingQuery;
import org.apache.spark.sql.streaming.StreamingQueryException;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;

import java.util.concurrent.TimeoutException;

public class StreamingApp {
    public static void main(String[] args) throws TimeoutException, StreamingQueryException {
        SparkSession spark = SparkSession
                .builder()
                .appName("KafkaSparkStreaming")
                .master("local[*]")
                .getOrCreate();

        spark.sparkContext().setLogLevel("WARN");

        // Cấu trúc JSON đầu vào
        StructType schema = new StructType()
                .add("id", DataTypes.IntegerType)
                .add("msg", DataTypes.StringType);

        // Đọc dữ liệu từ Kafka
        Dataset<Row> kafkaStream = spark
                .readStream()
                .format("kafka")
                .option("kafka.bootstrap.servers", "localhost:9092")
                .option("subscribe", "demo-topic")
                .option("startingOffsets", "earliest")
                .load();

        Dataset<Row> jsonData = kafkaStream
                .selectExpr("CAST(value AS STRING)")
                .select(functions.from_json(functions.col("value"), schema).as("data"))
                .select("data.*");

        // Ghi ra console và file
        StreamingQuery query = jsonData.writeStream()
                .format("console")
                .outputMode("append")
                .option("truncate", "false")
                .start();

        StreamingQuery csvOutput = jsonData.writeStream()
                .format("csv")
                .option("path", "output/csv")
                .option("checkpointLocation", "output/checkpoint")
                .outputMode("append")
                .start();

        query.awaitTermination();
        csvOutput.awaitTermination();
    }
}