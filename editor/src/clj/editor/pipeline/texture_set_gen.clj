;; Copyright 2020-2022 The Defold Foundation
;; Copyright 2014-2020 King
;; Copyright 2009-2014 Ragnar Svensson, Christian Murray
;; Licensed under the Defold License version 1.0 (the "License"); you may not use
;; this file except in compliance with the License.
;; 
;; You may obtain a copy of the License, together with FAQs at
;; https://www.defold.com/license
;; 
;; Unless required by applicable law or agreed to in writing, software distributed
;; under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
;; CONDITIONS OF ANY KIND, either express or implied. See the License for the
;; specific language governing permissions and limitations under the License.

(ns editor.pipeline.texture-set-gen
  (:require [dynamo.graph :as g]
            [editor.image-util :as image-util]
            [editor.protobuf :as protobuf]
            [editor.resource :as resource]
            [editor.resource-io :as resource-io]
            [util.coll :refer [pair]])
  (:import [com.dynamo.bob.textureset TextureSetGenerator TextureSetGenerator$AnimDesc TextureSetGenerator$AnimIterator TextureSetGenerator$TextureSetResult TextureSetLayout$Grid TextureSetLayout$Rect]
           [com.dynamo.bob.tile ConvexHull TileSetUtil TileSetUtil$Metrics]
           [com.dynamo.bob.util TextureUtil]
           [com.dynamo.gamesys.proto TextureSetProto$TextureSet$Builder]
           [com.dynamo.gamesys.proto Tile$ConvexHull Tile$Playback TextureSetProto$SpriteGeometry]
           [editor.types Image]
           [java.awt.image BufferedImage]))

(set! *warn-on-reflection* true)

(defn- anim->AnimDesc [anim]
  (when anim
    (TextureSetGenerator$AnimDesc. (:id anim) (protobuf/val->pb-enum Tile$Playback (:playback anim)) (:fps anim)
                                   (:flip-horizontal anim) (:flip-vertical anim))))

(defn- Rect->map
  [^TextureSetLayout$Rect rect]
  {:path (.id rect)
   :index (.index rect)
   :x (.x rect)
   :y (.y rect)
   :width (.width rect)
   :height (.height rect)
   :rotated (.rotated rect)})

(defn- Metrics->map
  [^TileSetUtil$Metrics metrics]
  (when metrics
    {:tiles-per-row (.tilesPerRow metrics)
     :tiles-per-column (.tilesPerColumn metrics)
     :tile-set-width (.tileSetWidth metrics)
     :tile-set-height (.tileSetHeight metrics)
     :visual-width (.visualWidth metrics)
     :visual-height (.visualHeight metrics)}))

(defn- TextureSetResult->result
  [^TextureSetGenerator$TextureSetResult tex-set-result]
  {:texture-set (protobuf/pb->map (.build (.builder tex-set-result)))
   :uv-transforms (vec (.uvTransforms tex-set-result))
   :layout (.layoutResult tex-set-result)
   :size [(.. tex-set-result layoutResult layout getWidth) (.. tex-set-result layoutResult layout getHeight)]
   :rects (into [] (map Rect->map) (.. tex-set-result layoutResult layout getRectangles))})

(defn layout-images
  [layout-result id->image]
  (TextureSetGenerator/layoutImages layout-result id->image))

(defn- sprite-trim-mode->hull-vertex-count
  ^long [sprite-trim-mode]
  (case sprite-trim-mode
    :sprite-trim-mode-off 0
    :sprite-trim-mode-4 4
    :sprite-trim-mode-5 5
    :sprite-trim-mode-6 6
    :sprite-trim-mode-7 7
    :sprite-trim-mode-8 8))

(defn- texture-set-layout-rect
  ^TextureSetLayout$Rect [{:keys [path width height]}]
  (let [id (resource/proj-path path)]
    (TextureSetLayout$Rect. id -1 (int width) (int height))))

(defonce ^:private ^TextureSetProto$SpriteGeometry rect-sprite-geometry-template
  (let [points (vector-of :float -0.5 -0.5 -0.5 0.5 0.5 0.5 0.5 -0.5)
        uvs (vector-of :float 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0)
        indices (vector-of :int 0 1 2 0 2 3)]
    (-> (TextureSetProto$SpriteGeometry/newBuilder)
        (.addAllVertices points)
        (.addAllUvs uvs)
        (.addAllIndices indices)
        (.buildPartial))))

(defn- make-rect-sprite-geometry
  ^TextureSetProto$SpriteGeometry [^long width ^long height]
  (-> (TextureSetProto$SpriteGeometry/newBuilder rect-sprite-geometry-template)
      (.setWidth width)
      (.setHeight height)
      (.build)))

(defn- make-image-sprite-geometry
  ^TextureSetProto$SpriteGeometry [^Image image]
  (let [error-node-id (:digest-ignored/error-node-id image)
        resource (.path image)
        sprite-trim-mode (.sprite-trim-mode image)]
    (assert (g/node-id? error-node-id))
    (assert (resource/resource? resource))
    (case sprite-trim-mode
      :sprite-trim-mode-off (make-rect-sprite-geometry (.width image) (.height image))
      (let [buffered-image (resource-io/with-error-translation resource error-node-id :image
                             (image-util/read-image resource))]
        (g/precluding-errors buffered-image
          (let [hull-vertex-count (sprite-trim-mode->hull-vertex-count sprite-trim-mode)]
            (TextureSetGenerator/buildConvexHull buffered-image hull-vertex-count)))))))

(defn atlas->texture-set-data
  [animations images margin inner-padding extrude-borders]
  (let [sprite-geometries (mapv make-image-sprite-geometry images)]
    (g/precluding-errors sprite-geometries
      (let [img-to-index (into {}
                               (map-indexed #(pair %2 (Integer/valueOf ^int %1)))
                               images)
            anims-atom (atom animations)
            anim-imgs-atom (atom [])
            anim-iterator (reify TextureSetGenerator$AnimIterator
                            (nextAnim [_this]
                              (let [anim (first @anims-atom)]
                                (reset! anim-imgs-atom (or (:images anim) []))
                                (swap! anims-atom rest)
                                (anim->AnimDesc anim)))
                            (nextFrameIndex [_this]
                              (let [img (first @anim-imgs-atom)]
                                (swap! anim-imgs-atom rest)
                                (img-to-index img)))
                            (rewind [_this]
                              (reset! anims-atom animations)
                              (reset! anim-imgs-atom [])))
            rects (mapv texture-set-layout-rect images)
            use-geometries (if (every? #(= :sprite-trim-mode-off (:sprite-trim-mode %)) images) 0 1)
            result (TextureSetGenerator/calculateLayout
                     rects sprite-geometries use-geometries anim-iterator margin inner-padding extrude-borders
                     true false nil)]
        (doto (.builder result)
          (.setTexture "unknown"))
        (TextureSetResult->result result)))))

(defn- calc-tile-start [{:keys [spacing margin]} size tile-index]
  (let [actual-tile-size (+ size spacing (* 2 margin))]
    (+ margin (* tile-index actual-tile-size))))

(defn- sub-image [tile-source-attributes tile-x tile-y image type]
  (let [w (:width tile-source-attributes)
        h (:height tile-source-attributes)
        tgt (BufferedImage. w h type)
        g (.getGraphics tgt)
        sx (calc-tile-start tile-source-attributes w tile-x)
        sy (calc-tile-start tile-source-attributes h tile-y)]
    (.drawImage g image 0 0 w h sx sy (+ sx w) (+ sy h) nil)
    (.dispose g)
    tgt))

(defn- split-image
  [image {:keys [tiles-per-column tiles-per-row] :as tile-source-attributes}]
  (let [type (TextureUtil/getImageType image)]
    (for [tile-y (range tiles-per-column)
          tile-x (range tiles-per-row)]
      (sub-image tile-source-attributes tile-x tile-y image type))))

(defn- split-rects
  [{:keys [width height tiles-per-column tiles-per-row] :as _tile-source-attributes}]
  (for [tile-y (range tiles-per-column)
        tile-x (range tiles-per-row)
        :let [index (+ tile-x (* tile-y tiles-per-row))
              name (format "tile%d" index)]]
    (TextureSetLayout$Rect. name
                            index
                            (* tile-x width)
                            (* tile-y height)
                            (int width)
                            (int height))))

(defn- tile-anim->AnimDesc [anim]
  (when anim
    (TextureSetGenerator$AnimDesc. (:id anim) (protobuf/val->pb-enum Tile$Playback (:playback anim)) (:fps anim)
                                   (not= 0 (:flip-horizontal anim)) (not= 0 (:flip-vertical anim)))))


(defn calculate-convex-hulls
  [^BufferedImage collision {:keys [width height margin spacing] :as _tile-properties}]
  (let [convex-hulls (TileSetUtil/calculateConvexHulls (.getAlphaRaster collision) 16 (.getWidth collision) (.getHeight collision)
                                                       width height margin spacing)
        points (vec (.points convex-hulls))]
    (mapv (fn [^ConvexHull hull]
            (let [index (.getIndex hull)
                  count (.getCount hull)]
              {:index index
               :count count
               :points (subvec points (* 2 index) (+ (* 2 index) (* 2 count)))}))
          (.hulls convex-hulls))))

(defn- metrics-rect [{:keys [width height]}]
  ;; The other attributes do not matter for metrics calculation.
  (TextureSetLayout$Rect. nil -1 (int width) (int height)))

(defn calculate-tile-metrics
  [image-size {:keys [width height margin spacing] :as _tile-properties} collision-size]
  (let [image-size-rect (metrics-rect image-size)
        collision-size-rect (when collision-size
                              (metrics-rect collision-size))
        metrics (TileSetUtil/calculateMetrics image-size-rect width height margin spacing collision-size-rect 1 0)]
    (Metrics->map metrics)))

(defn- add-collision-hulls!
  [^TextureSetProto$TextureSet$Builder builder convex-hulls collision-groups]
  (.addAllCollisionGroups builder collision-groups)
  (when convex-hulls
    (run! (fn [{:keys [index count points collision-group]}]
            (.addConvexHulls builder (doto (Tile$ConvexHull/newBuilder)
                                       (.setIndex index)
                                       (.setCount count)
                                       (.setCollisionGroup (or collision-group ""))))

            (run! #(.addCollisionHullPoints builder %) points))
          convex-hulls)))

(defn tile-source->texture-set-data [tile-source-attributes ^BufferedImage buffered-image convex-hulls collision-groups animations]
  (let [image-rects (split-rects tile-source-attributes)
        anims-atom (atom animations)
        anim-indices-atom (atom [])
        anim-iterator (reify TextureSetGenerator$AnimIterator
                        (nextAnim [_this]
                          (let [anim (first @anims-atom)]
                            (reset! anim-indices-atom (if anim
                                                        (vec (map int (range (dec (:start-tile anim)) (:end-tile anim))))
                                                        []))
                            (swap! anims-atom rest)
                            (tile-anim->AnimDesc anim)))
                        (nextFrameIndex [_this]
                          (let [index (first @anim-indices-atom)]
                            (swap! anim-indices-atom rest)
                            index))
                        (rewind [_this]
                          (reset! anims-atom animations)
                          (reset! anim-indices-atom [])))
        grid (TextureSetLayout$Grid. (:tiles-per-row tile-source-attributes) (:tiles-per-column tile-source-attributes))
        hull-vertex-count (sprite-trim-mode->hull-vertex-count (:sprite-trim-mode tile-source-attributes))
        sprite-geometries (map (fn [^TextureSetLayout$Rect image-rect]
                                 (let [sub-image (.getSubimage buffered-image (.x image-rect) (.y image-rect) (.width image-rect) (.height image-rect))]
                                   (TextureSetGenerator/buildConvexHull sub-image hull-vertex-count)))
                               image-rects)
        use-geometries (if (not= :sprite-trim-mode-off (:sprite-trim-mode tile-source-attributes)) 1 0)
        result (TextureSetGenerator/calculateLayout
                 image-rects
                 sprite-geometries
                 use-geometries
                 anim-iterator
                 (:margin tile-source-attributes)
                 (:inner-padding tile-source-attributes)
                 (:extrude-borders tile-source-attributes)
                 false true grid)]
    (doto (.builder result)
      (.setTileWidth (:width tile-source-attributes))
      (.setTileHeight (:height tile-source-attributes))
      (add-collision-hulls! convex-hulls collision-groups)
      ;; "This will be supplied later when producing the byte data for the pipeline"
      ;; TODO: check what that means and if it's true
      (.setTexture "unknown"))
    (TextureSetResult->result result)))

(defn layout-tile-source
  [layout-result ^BufferedImage image tile-source-attributes]
  (let [id->image (zipmap (map (fn [x] (format "tile%d" x)) (range)) (split-image image tile-source-attributes))]
    (TextureSetGenerator/layoutImages layout-result id->image)))
