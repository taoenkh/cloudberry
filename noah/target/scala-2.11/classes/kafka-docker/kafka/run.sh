#!/bin/bash

KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://kiwi.ics.uci.edu:9092
KAFKA_BROKER_ID=1
KAFKA_ZOOKEEPER_CONNECT=kiwi.ics.uci.edu:2181

VOLUME_DIR=/home/kafka-data-$KAFKA_BROKER_ID
sudo mkdir $VOLUME_DIR

docker run --detach -v $VOLUME_DIR:/tmp/kafka-logs -p 9092:9092 xizzz/kafka \
	$KAFKA_ADVERTISED_LISTENERS $KAFKA_BROKER_ID $KAFKA_ZOOKEEPER_CONNECT