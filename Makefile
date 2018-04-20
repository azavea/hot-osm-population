rwildcard=$(foreach d,$(wildcard $1*),$(call rwildcard,$d/,$2) $(filter $(subst *,%,$2),$d))

SCALA_SRC := $(call rwildcard, src/, *.scala)
SCALA_BLD := $(wildcard project/) build.sbt
ASSEMBLY_JAR := target/scala-2.11/hot-osm-population-assembly.jar
ECR_REPO := 670261699094.dkr.ecr.us-east-1.amazonaws.com/hotosm-population:latest

.PHONY: train predict docker push-ecr

${ASSEMBLY_JAR}: ${SCALA_SRC} ${SCALA_BLD}
	./sbt assembly

aws/task/hot-osm-population-assembly.jar: ${ASSEMBLY_JAR}
	cp $< $@

docker: aws/task/hot-osm-population-assembly.jar
	docker build aws/task -t hotosm-population

push-ecr:
	docker tag hotosm-population:latest ${ECR_REPO}
	docker push ${ECR_REPO}

train: ${ASSEMBLY_JAR}
	spark-submit --master "local[*]" --driver-memory 4G \
--class com.azavea.hotosmpopulation.TrainApp \
target/scala-2.11/hot-osm-population-assembly.jar \
--country BWA \
--worldpop file:/hot-osm/WorldPop/BWA15v4.tif \
--qatiles /hot-osm/mbtiles/botswana.mbtiles \
--model /hot-osm/models/BWA-avg-32

predict: ${ASSEMBLY_JAR}
	spark-submit --master "local[*]" --driver-memory 4G \
--class com.azavea.hotosmpopulation.PredictApp \
target/scala-2.11/hot-osm-population-assembly.jar \
--country BWA \
--worldpop file:/hot-osm/WorldPop/BWA15v4.tif \
--qatiles /hot-osm/mbtiles/botswana.mbtiles \
--model /hot-osm/models/BWA-avg-32 \
--output /hot-osm/botswana-predict-percentage.json