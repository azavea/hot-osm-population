# Training Data

We aim to derive relationship between population density in WorldPop raster `(population / pixel)` and Open Street Map building footprint coverage `(square meters / pixel)`.
This relationship can break down because OSM and WorldPop have different biases and different update intervals.

In order to avoid training on regions that have this problem we need to identify training regions.
The training sets are consumed as GeoJSON `FeatureCollection` of `MultiPolygon`s in `epsg:3857` projection.  

## Generation

A good technique for generating a training set is to use QGIS with following layers: 

- WorldPop Raster
- Rasterized OSM Building Footprints or OSM WMS with building footprints
- MapBox Satellite Streets WMS layer

Following are examples of areas labeled for training: 

**Legend**:
- `WorldPop`: light-blue to deep-blue
- `OSM Building`: green/orange/yellow
- `Training Area`: green polygon

### Good OSM/WorldPop Coverage 
WorldPop has detected high population density area and OSM building coverage looks complete.
<img width="677" alt="good-coverage-1" src="https://user-images.githubusercontent.com/1158084/39271843-2f3251d6-48a8-11e8-9736-082fc1a7bd65.png">

Visually verify with MapBox satellite streets layer that OSM coverage is good 
<img width="622" alt="good-coverage-2" src="https://user-images.githubusercontent.com/1158084/39271846-30f20462-48a8-11e8-9271-b315ab2f09b7.png">

Label city and surrounding area as training area
<img width="672" alt="good-coverage-3" src="https://user-images.githubusercontent.com/1158084/39271868-40fd701c-48a8-11e8-955a-7baaffaeceb0.png">

### OSM newer than WorldPop raster
OSM building footprints but WorldPop is shows no change in population density.
<img width="312" alt="no-worldpop-coverage-1" src="https://user-images.githubusercontent.com/1158084/39271888-4eb25876-48a8-11e8-8d67-f43c3b09a1d5.png">

Satellite verifies that something really is there. 
<img width="401" alt="no-worldpop-coverage-2" src="https://user-images.githubusercontent.com/1158084/39271893-50e15598-48a8-11e8-87b7-b1d60ac873a0.png">

Do not label this, it would indicate that no population implies building coverage for our training set. 

### Partial OSM Coverage over WorldPop
Here we have a high population density area that is partially mapped.
<img width="719" alt="partial-osm-coverage-1" src="https://user-images.githubusercontent.com/1158084/39271920-6a456286-48a8-11e8-8ed0-53773b6faa42.png">

Satellite show lots of unlabelled buildings.
<img width="768" alt="partial-osm-coverage-2" src="https://user-images.githubusercontent.com/1158084/39271925-6da7abd2-48a8-11e8-9478-96ee9a31213c.png">

Draw training area around well lebeled blocks of the city to capture example of dense urban region.

### OSM Coverage in Low density Area, WorldPop mislabels farms
Here we have example of low density area
<img width="609" alt="screen shot 2018-04-25 at 4 58 57 pm" src="https://user-images.githubusercontent.com/1158084/39272511-189d1058-48aa-11e8-93b8-c09911b99113.png">

WorldPop labelled the surrounding farmland at same density as city center
<img width="534" alt="screen shot 2018-04-25 at 4 59 05 pm" src="https://user-images.githubusercontent.com/1158084/39272515-1b273d76-48aa-11e8-82d5-99ea70542351.png">

Label the city and surround areas
<img width="579" alt="screen shot 2018-04-25 at 4 59 12 pm" src="https://user-images.githubusercontent.com/1158084/39272519-1d5b4c0e-48aa-11e8-9bc9-2be14a54b6df.png">


