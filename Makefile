rwildcard=$(foreach d,$(wildcard $1*),$(call rwildcard,$d/,$2) $(filter $(subst *,%,$2),$d))

SCALA_SRC := $(call rwildcard, src/, *.scala)
SCALA_BLD := $(wildcard project/) build.sbt

target/scala-2.11/hot-osm-population-assembly.jar: ${SCALA_SRC} ${SCALA_BLD}
	./sbt assembly

task/hot-osm-population-assembly.jar: target/scala-2.11/hot-osm-population-assembly.jar
	cp $< $@

docker: target/scala-2.11/hot-osm-population-assembly.jar
	docker build task -t hot-osm-population

ecr-publish:
	docker tag hot-osm-population:latest 896538046175.dkr.ecr.us-east-1.amazonaws.com/hot-osm-population:latest
	docker push 896538046175.dkr.ecr.us-east-1.amazonaws.com/hot-osm-population:latest

predict:
	docker run -it --rm \
-e COMMAND=predict -e COUNTRY_CODE=BWA -e COUNTRY=botswana \
-e WORLDPOP=s3://hot-osm/WorldPop/BWA15v4.tif \
-e MODEL=s3://hot-osm/models/avg-38/ \
-e OUTPUT=s3://hot-osm/botswana-predict.json \
-v ~/.aws:/root/.aws \
hotosm