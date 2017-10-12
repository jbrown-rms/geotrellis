/*
 * Copyright 2016 Azavea
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package geotrellis.spark.filter

import geotrellis.spark._
import geotrellis.util._

import org.apache.spark.Partitioner
import org.apache.spark.rdd._

import scala.reflect.ClassTag


object ToSpatial {
  /**
    * Restrict a tile layer and its metadata to two spatial dimensions.
    *
    * To project not only Tiles, but also Metadata (M) information
    * and to get a consistent result type, it is possible
    * to define additional constraints on Metadata. M should
    * depend on a K type (M[K]), and two type classes should
    * be provided: [[geotrellis.util.Component]], to extract key bounds
    * from M[K], and [[geotrellis.util.Functor]] to map
    * M[K] to M[SpatialKey].
    *
    * For those reading the source code directly,
    * {{{K: λ[α => M[α] => Functor[M, α]]: λ[α => Component[M[α], Bounds[α]]}}}
    * is further syntax sugar on top of the usual {{{K: ...}}} pattern.
    * It expands into the following Scala implicit evidences:
    *
    * {{{
    *   ev0: Component[M[K], Bounds[K]],
    *   ev1: M[K] => Functor[M, K]
    * }}}
    *
    * @param rdd
    * @param instant
    * @tparam K
    * @tparam V
    * @tparam M
    * @return
    */
  def apply[
    K: SpatialComponent: TemporalComponent: λ[α => M[α] => Functor[M, α]]: λ[α => Component[M[α], Bounds[α]]],
    V,
    M[_]
  ](rdd: RDD[(K, V)] with Metadata[M[K]], instant: Long): RDD[(SpatialKey, V)] with Metadata[M[SpatialKey]] = {
    rdd.metadata.getComponent[Bounds[K]] match {
      case KeyBounds(minKey, maxKey) =>
        val minInstant = minKey.getComponent[TemporalKey].instant
        val maxInstant = maxKey.getComponent[TemporalKey].instant

        if(instant < minInstant || maxInstant < instant) {
          val md = rdd.metadata.setComponent[Bounds[K]](EmptyBounds)
          ContextRDD(rdd.sparkContext.parallelize(Seq()), md.map(_.getComponent[SpatialKey]))
        } else {
          val filteredRdd =
            rdd
              .flatMap { case (key, tile) =>
                if (key.getComponent[TemporalKey].instant == instant)
                  Some((key.getComponent[SpatialKey], tile))
                else
                  None
            }

          val newBounds =
            KeyBounds(
              minKey.setComponent[TemporalKey](TemporalKey(instant)),
              maxKey.setComponent[TemporalKey](TemporalKey(instant))
            )

          val md = rdd.metadata.setComponent[Bounds[K]](newBounds)

          ContextRDD(filteredRdd, md.map(_.getComponent[SpatialKey]))
        }
      case EmptyBounds =>
        ContextRDD(
          rdd.sparkContext.parallelize(Seq()),
          rdd.metadata.map(_.getComponent[SpatialKey])
        )
    }
  }

  def apply[
    K: ClassTag: SpatialComponent: TemporalComponent: λ[α => M[α] => Functor[M, α]]: λ[α => Component[M[α], Bounds[α]]],
    V: ClassTag,
    M[_]
  ](
    rdd: RDD[(K, V)] with Metadata[M[K]],
    ensureUnique: Boolean,
    partitioner: Option[Partitioner] = None
  ): RDD[(SpatialKey, V)] with Metadata[M[SpatialKey]] = {
    val metadata = rdd.metadata.map(_.getComponent[SpatialKey])
    val rdd2 = (ensureUnique,partitioner) match {
      case (true, Some(partitioner)) =>
        rdd
          .map({ case (k, v) => (k.getComponent[SpatialKey], v) })
          .map(identity).reduceByKey(partitioner, {(v: V, _: V) => v})

      case (true, None) =>
        rdd
          .map({ case (k, v) => (k.getComponent[SpatialKey], v) })
          .map(identity).reduceByKey({ case (left, right) => left })

      case (false, _) =>
        rdd
          .map({ case (k, v) => (k.getComponent[SpatialKey], v) })
    }
    ContextRDD(rdd2, metadata)
  }
}
