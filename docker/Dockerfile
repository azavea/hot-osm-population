FROM openjdk:8-jre

RUN apt-get update && apt-get install -y libnss3 curl python python-pip && pip install --upgrade awscli && apt-get clean
RUN mkdir /task
WORKDIR /task

ENV SPARK_HOME /opt/spark
RUN mkdir -p /opt/spark
RUN curl http://www.trieuvan.com/apache/spark/spark-2.2.1/spark-2.2.1-bin-hadoop2.7.tgz \
    | tar --strip-components=1 -xzC /opt/spark
COPY log4j.properties /opt/spark/conf

COPY hot-osm-population-assembly.jar /
COPY task.sh /

ENTRYPOINT ["/task.sh"]