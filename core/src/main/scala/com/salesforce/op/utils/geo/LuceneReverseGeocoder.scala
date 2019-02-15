/*
 * Copyright (c) 2017, Salesforce.com, Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 *
 * * Neither the name of the copyright holder nor the names of its
 *   contributors may be used to endorse or promote products derived from
 *   this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.salesforce.op.utils.geo

import java.io.{BufferedReader, InputStreamReader}
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

import org.apache.lucene.document.{Document, NumericDocValuesField, StoredField}
import org.apache.lucene.index.IndexWriterConfig.OpenMode
import org.apache.lucene.index.{DirectoryReader, IndexWriter, IndexWriterConfig}
import org.apache.lucene.search.{IndexSearcher, Sort, SortField, TopDocs}
import org.apache.lucene.spatial.SpatialStrategy
import org.apache.lucene.spatial.prefix.RecursivePrefixTreeStrategy
import org.apache.lucene.spatial.prefix.tree.GeohashPrefixTree
import org.apache.lucene.spatial.query.{SpatialArgs, SpatialOperation}
import org.apache.lucene.store.BaseDirectory
import org.locationtech.spatial4j.context.SpatialContext
import org.locationtech.spatial4j.distance.DistanceUtils
import org.locationtech.spatial4j.shape.Point
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.concurrent.duration.Duration
import scala.util.Try


// scalastyle:off
/**
 * Reverse Geocoder implementation using Lucene spatial index.
 *
 * TODO: add more details on the implementation
 *
 * Related read:
 * 1. https://opensourceconnections.com/blog/2014/04/11/indexing-polygons-in-lucene-with-accuracy
 * 2. https://github.com/apache/lucene-solr/blob/branch_7x/lucene/spatial-extras/src/test/org/apache/lucene/spatial/SpatialExample.java
 */
// scalastyle:on
class LuceneReverseGeocoder extends ReverseGeocoder {

  /**
   * Find the nearest cities to the specified coordinate within the radius in KM
   *
   * @param latitude     latitude value
   * @param longitude    longitude value
   * @param radiusInKM   radius in KM
   * @param numOfResults number of results to return
   * @return nearest cities to the specified coordinate within the radius in KM
   */
  def nearestCities(latitude: Double, longitude: Double, radiusInKM: Double, numOfResults: Int): Seq[WorldCity] = {
    nearestCities(
      searcher = LuceneReverseGeocoder.worldCities,
      latitude = latitude, longitude = longitude,
      radiusInKM = radiusInKM, numOfResults = numOfResults)
  }

  /**
   * Find the nearest countries to the specified coordinate within the radius in KM
   *
   * @param latitude     latitude value
   * @param longitude    longitude value
   * @param radiusInKM   radius in KM
   * @param numOfResults number of results to return
   *
   * @return nearest cities to the specified coordinate within the radius in KM
   */
  def nearestCountries(latitude: Double, longitude: Double, radiusInKM: Double, numOfResults: Int): Seq[String] = {
    nearestCountries(
      searcher = LuceneReverseGeocoder.worldCities,
      latitude = latitude, longitude = longitude,
      radiusInKM = radiusInKM, numOfResults = numOfResults)
  }

  /**
   * Find the nearest cities to the specified coordinate within the radius in KM
   *
   * @param searcher     world cities index searcher
   * @param latitude     latitude value
   * @param longitude    longitude value
   * @param radiusInKM   radius in KM
   * @param numOfResults number of results to return
   * @return nearest cities to the specified coordinate within the radius in KM
   */
  private[geo] def nearestCountries
  (
    searcher: IndexSearcher,
    latitude: Double,
    longitude: Double,
    radiusInKM: Double,
    numOfResults: Int
  ): Seq[String] = {
    val cities = nearestCities(
      searcher = searcher,
      latitude = latitude, longitude = longitude,
      radiusInKM = radiusInKM, numOfResults = numOfResults)

    cities.map(_.country).distinct
  }

  /**
   * Find the nearest cities to the specified coordinate within the radius in KM
   *
   * @param searcher     world cities index searcher
   * @param latitude     latitude value
   * @param longitude    longitude value
   * @param radiusInKM   radius in KM
   * @param numOfResults number of results to return
   * @return nearest cities to the specified coordinate within the radius in KM
   */
  private[geo] def nearestCities
  (
    searcher: IndexSearcher,
    latitude: Double,
    longitude: Double,
    radiusInKM: Double,
    numOfResults: Int
  ): Seq[WorldCity] = {
    import LuceneReverseGeocoder._

    val idSort = new Sort(new SortField("id", SortField.Type.INT))
    val deg = DistanceUtils.dist2Degrees(radiusInKM, DistanceUtils.EARTH_MEAN_RADIUS_KM)
    val point = makePoint(latitude = latitude, longitude = longitude)
    val circle = context.getShapeFactory.circle(longitude, latitude, deg)
    val args = new SpatialArgs(SpatialOperation.Intersects, circle)
    val query = strategy.makeQuery(args)

    val docs: TopDocs = searcher.search(query, numOfResults, idSort)
    val cities = docs.scoreDocs.map { hit =>
      val doc = searcher.doc(hit.doc)
      makeCity(doc)
    }
    // Sort cities by ascending distance
    cities.sortBy(city => calcDistance(point, latitude = city.latitude, longitude = city.longitude))
  }

}


/**
 * Helper facilities to build, read and query Lucene reverse geocoder index
 */
private[geo] case object LuceneReverseGeocoder {

  lazy val log = LoggerFactory.getLogger(classOf[LuceneReverseGeocoder])

  /**
   * World cities data (used to construct the index offline)
   */
  lazy val worldCitiesData: Seq[WorldCity] = loadWorldCitiesData()

  /**
   * Default SpatialContext implementation for geospatial
   */
  val context: SpatialContext = SpatialContext.GEO

  /**
   * Spatial strategy
   */
  val strategy: SpatialStrategy = {
    val grid = new GeohashPrefixTree(context, 11)
    new RecursivePrefixTreeStrategy(grid, "geoLoc")
  }

  /**
   * World cities index
   */
  lazy val worldCities: IndexSearcher = throw new NotImplementedError // TODO: implement default index

  /**
   * Builds reverse geocoder index and saves it into the specified directory
   *
   * @param cities    cities to index
   * @param directory directory to save the index
   * @return elapsed time in ms
   */
  def buildIndex(cities: Seq[WorldCity], directory: BaseDirectory): Try[Long] = Try {
    val start = System.currentTimeMillis()
    log.info(s"Building index with ${cities.size} cities...")

    val iwConfig = new IndexWriterConfig(null).setOpenMode(OpenMode.CREATE)
    val indexWriter = new IndexWriter(directory, iwConfig)

    for {
      (city, id) <- cities.zipWithIndex
      point = makePoint(latitude = city.latitude, longitude = city.longitude)
      doc = makeDocument(id, city)
    } {
      strategy.createIndexableFields(point).foreach(doc.add)
      indexWriter.addDocument(doc)
    }
    indexWriter.close()

    val elapsed = Duration.apply(System.currentTimeMillis() - start, TimeUnit.MILLISECONDS)
    val docSec = if (elapsed.toSeconds > 0) cities.size / elapsed.toSeconds else cities.size
    log.info(s"Elapsed ${elapsed.toSeconds} seconds ($docSec doc/sec). Index saved to '$directory'.")
    elapsed.toMillis
  }

  /**
   * Open index from a given directory
   *
   * @param directory directory to load the index
   * @return index searcher
   */
  def openIndex(directory: BaseDirectory): Try[IndexSearcher] = Try {
    val indexReader = DirectoryReader.open(directory)
    new IndexSearcher(indexReader)
  }

  /**
   * Load World Cities dataset - https://www.kaggle.com/max-mind/world-cities-database
   */
  def loadWorldCitiesData(): Seq[WorldCity] = {
    val start = System.currentTimeMillis()
    val dataPath = Paths.get("data/world-cities-database.zip").toFile.getCanonicalFile.getAbsoluteFile
    log.info(s"Loading world cities data from: $dataPath")

    val citiesCsv = {
      val zip = new ZipFile(dataPath)
      val in = zip.getInputStream(zip.entries().asScala.find(_.getName == "worldcitiespop.csv").get)
      new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))
    }.lines().iterator().asScala.drop(1) // skip header

    val cities = citiesCsv.map { c =>
      val Array(country, city, accentCity, region, population, latitude, longitude) = c.split(',')
      WorldCity(
        country = country,
        city = city,
        accentCity = accentCity,
        region = region,
        population = if (population.isEmpty) -1 else population.toDouble.toLong,
        latitude = latitude.toDouble,
        longitude = longitude.toDouble
      )
    }.toSeq

    val elapsed = Duration.apply(System.currentTimeMillis() - start, TimeUnit.MILLISECONDS)
    log.info(s"Loaded ${cities.size} cities. Elapsed ${elapsed.toSeconds} seconds.")
    cities
  }

  /**
   * Make [[Point]] from lat and lon values, where X - longitude, Y - latitude
   */
  def makePoint(latitude: Double, longitude: Double): Point =
    context.getShapeFactory.pointXY(longitude, latitude) // X - longitude, Y - latitude

  /**
   * Calculate distance between a [[Point]] and lat and lon values
   */
  def calcDistance(p: Point, latitude: Double, longitude: Double): Double =
    context.calcDistance(p, longitude, latitude) // X - longitude, Y - latitude

  /**
   * Make Lucene [[Document]] instance for indexing
   */
  def makeDocument(id: Int, city: WorldCity): Document = {
    val doc = new Document()
    doc.add(new NumericDocValuesField("id", id))
    doc.add(new StoredField("city", city.city))
    doc.add(new StoredField("country", city.country))
    doc.add(new StoredField("accentCity", city.accentCity))
    doc.add(new StoredField("region", city.region))
    doc.add(new StoredField("population", city.population))
    doc.add(new StoredField("latitude", city.latitude))
    doc.add(new StoredField("longitude", city.longitude))
    doc
  }

  /**
   * Make city class instance from Lucene [[Document]] for index retrieval
   */
  def makeCity(doc: Document): WorldCity = {
    WorldCity(
      city = doc.get("city"),
      country = doc.get("country"),
      accentCity = doc.get("accentCity"),
      region = doc.get("region"),
      population = doc.get("population").toLong,
      latitude = doc.get("latitude").toDouble,
      longitude = doc.get("longitude").toDouble
    )
  }

}