(ns hc.server.documents
  "Safe document extraction facade.

  Public contract:

      (extract! request) -> result map

  `request` carries a source descriptor, one explicit `:selection :route`, and
  optional page/password/OCR/image options.  Expected refusals are result maps
  with `:ok? false`; callers do not need to catch exceptions.

  Text and PDF extraction use Apache PDFBox; OCR uses local CPU Tesseract via
  JavaCPP with bundled English and Simplified Chinese data.  Images are
  oriented from EXIF and scaled to a long-edge limit before sending or OCR.

  See `src/hc/server/documents/AGENTS.md` for the full contract, error codes
  and privacy boundaries."
  (:require [hc.server.documents.extract :as extract]
            [hc.server.documents.image :as image]
            [hc.server.documents.ocr :as ocr]
            [hc.server.documents.ocr.tesseract :as tesseract]
            [hc.server.documents.pages :as pages]
            [hc.server.documents.selection :as selection]
            [hc.server.documents.text :as text]))

(def extract! extract/extract!)

(def preprocess-image image/preprocess)
(def parse-pages pages/parse-range)
(def validate-pages pages/validate)
(def select-route selection/select-route)
(def processing selection/processing)

(def tesseract-engine tesseract/engine)
(def ocr-availability ocr/availability)
(def decode-text text/decode)

(defn available?
  "True when the bundled local OCR engine can run now."
  []
  (boolean (:available? (ocr/availability (tesseract/engine)))))

(defn ocr-status
  "Full availability map for the bundled local OCR engine."
  []
  (ocr/availability (tesseract/engine)))

(defn service
  "A plain map for application assembly.  Extraction is stateless; this map
  only gives the router stable names for the public operations."
  []
  {:extract! extract!
   :preprocess! image/preprocess
   :parse-pages pages/parse-range
   :validate-pages pages/validate
   :select-route selection/select-route
   :ocr-status ocr-status
   :ocr-available? available?})
