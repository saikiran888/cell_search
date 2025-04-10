package qupath.ext.template

import javafx.beans.value.ChangeListener
import javafx.concurrent.Task
import javafx.scene.control.Alert.AlertType
import qupath.lib.common.Version
import qupath.lib.gui.extensions.QuPathExtension
import qupath.lib.objects.PathObject
import org.apache.commons.math3.ml.distance.EuclideanDistance
import java.util.logging.*
import qupath.lib.roi.ROIs
import qupath.lib.objects.PathAnnotationObject
import javafx.geometry.Pos
import qupath.lib.gui.QuPathGUI
import javafx.stage.Modality
import javafx.application.Platform
import qupath.lib.objects.classes.PathClass
import java.awt.Color
import javafx.stage.FileChooser
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.layout.*
import javafx.stage.Stage
import javafx.geometry.Insets



/**
 *
 * A QuPath extension that demonstrates a 'Cell Search Engine' with:
 *  - Quick Search (Morphology, Marker, Combined, Neighborhood)
 *  - Comprehensive Search (CSV-based clustering)
 *  phenotype finder
 *

 */
class DemoGroovyExtension implements QuPathExtension {
	private static List<Map<String, String>> cachedCSVRows = null
	private static String cachedCSVPath = null

	String name = "Cell Search Engine"
	String description = "Offers quick and comprehensive cell similarity searches."
	Version QuPathVersion = Version.parse("v0.4.0")

	@Override
	void installExtension(QuPathGUI qupath) {
		def mainMenu = qupath.getMenu("Extensions>" + name, true)

		// --- QUICK CELL SEARCH ---
		def quickSearchMenu = new Menu("Quick Cell Search")

		def morphologyItem = new MenuItem("Morphology-based Search")
		morphologyItem.setOnAction(e -> {
			runQuickSearch(qupath, "morphology")
		})

		def markerItem = new MenuItem("Marker-based Search")
		markerItem.setOnAction(e -> {
			runQuickSearch(qupath, "marker")
		})

		def combinedItem = new MenuItem("Combined Search")
		combinedItem.setOnAction(e -> {
			runQuickSearch(qupath, "combined")
		})

		def neighborhoodItem = new MenuItem("Similar Neighborhood Search")
		neighborhoodItem.setOnAction(e -> {
			runNeighborhoodSearch(qupath)
		})


		def multiQueryItem = new MenuItem("Multi-Query Search")
		multiQueryItem.setOnAction(e -> { runMultiQuerySearch(qupath) })
		quickSearchMenu.getItems().addAll(morphologyItem, markerItem, combinedItem, neighborhoodItem,multiQueryItem)

		// --- COMPREHENSIVE SEARCH ---
		def comprehensiveMenu = new Menu("Comprehensive Search")


		def csvClusterItem = new MenuItem("Community Search")
		csvClusterItem.setOnAction(e -> runCSVClusterSearch(qupath))
		comprehensiveMenu.getItems().add(csvClusterItem)

		def phenotypeFinderItem = new MenuItem("Phenotype Finder")
		phenotypeFinderItem.setOnAction(e -> runPhenotypeFinder(qupath))
		comprehensiveMenu.getItems().add(phenotypeFinderItem)

		mainMenu.getItems().addAll(quickSearchMenu, comprehensiveMenu)
	}





	private static void runQuickSearch(QuPathGUI qupath, String searchType) {
		def imageData = qupath.getImageData()
		if (imageData == null) {
			def alert =new Alert(AlertType.WARNING, "No image data available.")
			alert.initOwner(qupath.getStage())
			alert.showAndWait()
			return
		}
		def hierarchy = imageData.getHierarchy()
		def selectedCells = hierarchy.getSelectionModel().getSelectedObjects().findAll { it.isCell() }
		if (selectedCells.isEmpty()) {
			def alert =new Alert(AlertType.WARNING, "Please select a single cell before running the search!")
			alert.initOwner(qupath.getStage())
			alert.showAndWait()
			return

		}
		def targetCell = selectedCells[0]
		println "Selected cell: ID = ${targetCell.getID()}"

		def cells = hierarchy.getDetectionObjects().findAll { it.isCell() }
		double[] targetFeatures
		switch (searchType) {
			case "morphology":
				targetFeatures = extractMorphologicalFeatures(targetCell)
				break
			case "marker":
				targetFeatures = extractMarkerFeatures(targetCell)
				break
			case "combined":
				targetFeatures = extractCombinedFeatures(targetCell)
				break
			default:
				targetFeatures = extractMarkerFeatures(targetCell)
		}

		// Compute Euclidean distances from target cell to every other cell.
		def distances = cells.findAll { it != targetCell }.collect { cell ->
			double[] cellFeatures
			switch (searchType) {
				case "morphology":
					cellFeatures = extractMorphologicalFeatures(cell)
					break
				case "marker":
					cellFeatures = extractMarkerFeatures(cell)
					break
				case "combined":
					cellFeatures = extractCombinedFeatures(cell)
					break
				default:
					cellFeatures = extractMarkerFeatures(cell)
			}
			double dist = new EuclideanDistance().compute(targetFeatures, cellFeatures)
			[cell, dist]
		}

		distances.sort { it[1] }
		def topCells = distances.take(4000).collect { it[0] }
		def allSelected = [targetCell] + topCells

		def redClass = PathClass.fromString("Highlighted-Red")
		allSelected.each { it.setPathClass(redClass) }

		def selectionModel = hierarchy.getSelectionModel()
		selectionModel.clearSelection()
		selectionModel.setSelectedObjects(allSelected as List<PathObject>, targetCell as PathObject)


		println "Quick search '${searchType}' complete. Highlighted ${topCells.size()} similar cells."
	}


// QuPath Extension: Neighborhood Search

// Fully Integrated Neighborhood Search with Center + Morph + Surround Logic (Optimized)
	private static void runNeighborhoodSearch(QuPathGUI qupath) {
		def imageData = qupath.getImageData()
		if (imageData == null) {
			def alert = new Alert(Alert.AlertType.WARNING, "No image data available.")
			alert.initOwner(qupath.getStage())
			alert.show()
			return
		}

		def hierarchy = imageData.getHierarchy()
		def allDetections = hierarchy.getDetectionObjects().findAll { it.isCell() }
		if (allDetections.isEmpty()) {
			def alert = new Alert(Alert.AlertType.WARNING, "No cell detections found to extract marker names.")
			alert.initOwner(qupath.getStage())
			alert.show()
			return
		}

		def measurementNames = allDetections[0].getMeasurementList().getMeasurementNames()
		def markerLabels = measurementNames.findAll { it.startsWith("Cell: ") && it.endsWith(" mean") }
		markerLabels = markerLabels.collect { it.replace("Cell: ", "").replace(" mean", "") }

		def markerCheckboxes = markerLabels.collect { new CheckBox(it) }
		CheckBox cbMarkerSelectAll = new CheckBox("Select All")
		cbMarkerSelectAll.setOnAction {
			boolean value = cbMarkerSelectAll.isSelected()
			markerCheckboxes.each { it.setSelected(value) }
		}

		def surroundCheckboxes = markerLabels.collect { new CheckBox(it) }
		CheckBox cbSurroundSelectAll = new CheckBox("Select All")
		cbSurroundSelectAll.setOnAction {
			boolean value = cbSurroundSelectAll.isSelected()
			surroundCheckboxes.each { it.setSelected(value) }
		}

		def partitionCheckboxes = { List<CheckBox> checkboxes, int numColumns ->
			int itemsPerColumn = (int) Math.ceil(checkboxes.size() / (double) numColumns)
			def columns = []
			for (int i = 0; i < numColumns; i++) {
				int start = i * itemsPerColumn
				int end = Math.min(start + itemsPerColumn, checkboxes.size())
				def column = new VBox(5)
				checkboxes.subList(start, end).each { column.getChildren().add(it) }
				columns << column
			}
			def hbox = new HBox(10)
			columns.each { hbox.getChildren().add(it) }
			return hbox
		}

		VBox markerBox = new VBox(5, new Label("Center-cell Markers:"), cbMarkerSelectAll, partitionCheckboxes(markerCheckboxes, 4))
		VBox surroundBox = new VBox(5, new Label("Neighborhood Markers:"), cbSurroundSelectAll, partitionCheckboxes(surroundCheckboxes, 4))

		CheckBox cbArea = new CheckBox("Area")
		CheckBox cbPerimeter = new CheckBox("Perimeter")
		CheckBox cbCircularity = new CheckBox("Circularity")
		CheckBox cbMaxCaliper = new CheckBox("Max caliper")
		CheckBox cbMinCaliper = new CheckBox("Min caliper")
		CheckBox cbEccentricity = new CheckBox("Eccentricity")
		CheckBox cbMorphSelectAll = new CheckBox("Select All")
		cbMorphSelectAll.setOnAction {
			boolean value = cbMorphSelectAll.isSelected()
			[cbArea, cbPerimeter, cbCircularity, cbMaxCaliper, cbMinCaliper, cbEccentricity].each { it.setSelected(value) }
		}
		VBox morphBox = new VBox(5, new Label("Center-cell Morphology:"), cbMorphSelectAll, new HBox(10, new VBox(5, cbArea, cbPerimeter), new VBox(5, cbCircularity, cbMaxCaliper), new VBox(5, cbMinCaliper, cbEccentricity)))

		// Add UI for radius and Top N (handled in main logic)
		Label radiusLabel = new Label("Radius (microns):")
		TextField tfRadius = new TextField("50")
		Label topNLabel = new Label("Top N:")
		TextField tfTopN = new TextField("4000")

		Button btnGo = new Button("GO")
		Button btnReset = new Button("Reset")
		Button btnExport = new Button("Save Set")

		def circleAnnotationRef = null

		btnGo.setOnAction {
			// Create progress UI
			ProgressBar progressBar = new ProgressBar(0)
			progressBar.setPrefWidth(300)
			Label progressLabel = new Label("Running search...")
			VBox progressBox = new VBox(10, progressLabel, progressBar)
			progressBox.setPadding(new Insets(20))
			Stage progressStage = new Stage()
			progressStage.setTitle("Neighborhood Search Progress")
			progressStage.initOwner(qupath.getStage())
			progressStage.setScene(new Scene(progressBox))
			progressStage.show()

			long startTime = System.currentTimeMillis()
			def selectedCells = hierarchy.getSelectionModel().getSelectedObjects().findAll { it.isCell() }
			if (selectedCells.isEmpty()) {
				new Alert(Alert.AlertType.WARNING, "Please select a single cell before running neighborhood search!").show()
				return
			}

			def targetCell = selectedCells[0]
			hierarchy.getDetectionObjects().findAll { it.isCell() }.each { it.setPathClass(null) }
			hierarchy.getSelectionModel().clearSelection()
			if (circleAnnotationRef != null) {
				hierarchy.removeObject(circleAnnotationRef, false)
				circleAnnotationRef = null
			}

			double radiusMicrons
			try {
				radiusMicrons = Double.parseDouble(tfRadius.getText())
			} catch (Exception e) {
				new Alert(Alert.AlertType.WARNING, "Invalid radius value.").show()
				return
			}

			int topN
			try {
				topN = Integer.parseInt(tfTopN.getText())
			} catch (Exception e) {
				new Alert(Alert.AlertType.WARNING, "Invalid Top N value.").show()
				return
			}

			def roi = targetCell.getROI()
			double centerX = roi.getCentroidX()
			double centerY = roi.getCentroidY()
			double pixelSize = imageData.getServer().getPixelCalibration().getPixelWidthMicrons()
			double radiusPixels = radiusMicrons / pixelSize

			def circleROI = ROIs.createEllipseROI(centerX - radiusPixels, centerY - radiusPixels, 2 * radiusPixels, 2 * radiusPixels, roi.getImagePlane())
			circleAnnotationRef = new PathAnnotationObject(circleROI)
			def circleClass = PathClass.fromString("Circle")
			circleAnnotationRef.setPathClass(circleClass)
			hierarchy.addObject(circleAnnotationRef, false)
			Platform.runLater { qupath.getViewer().repaint() }

			def allCells = hierarchy.getDetectionObjects().findAll { it.isCell() }
			def finalCells = []

			boolean markerSelected = markerCheckboxes.any { it.isSelected() }
			boolean morphSelected = [cbArea, cbPerimeter, cbCircularity, cbMaxCaliper, cbMinCaliper, cbEccentricity].any { it.isSelected() }
			boolean surroundSelected = surroundCheckboxes.any { it.isSelected() }

			if (surroundSelected) {
				Task<Void> task = new Task<Void>() {
					@Override
					protected Void call() throws Exception {
						def selectedMarkers = surroundCheckboxes.findAll { it.isSelected() }*.getText().collect { "Cell: ${it} mean" }
						def targetNeighborhood = allCells.findAll { cell ->
							def dx = cell.getROI().getCentroidX() - centerX
							def dy = cell.getROI().getCentroidY() - centerY
							return (dx*dx + dy*dy) <= radiusPixels*radiusPixels
						}
						if (targetNeighborhood.isEmpty()) return null
						def avgVec = selectedMarkers.collect { marker ->
							def values = targetNeighborhood.collect { c -> c.getMeasurementList().getMeasurementValue(marker) ?: 0.0 }
							values.sum() / values.size()
						}
						def cellCoordinates = allCells.collectEntries { cell ->
							def c = cell.getROI()
							[(cell): [c.getCentroidX(), c.getCentroidY()]]
						}
						def cellMarkerMap = allCells.collectEntries { cell ->
							[(cell): selectedMarkers.collect { m -> cell.getMeasurementList().getMeasurementValue(m) ?: 0.0 }]
						}
						def radiusSq = radiusPixels * radiusPixels
						def cellsToProcess = allCells.findAll { it != targetCell }
						int totalSteps = cellsToProcess.size()
						int[] step = [0]
						def distances = cellsToProcess.collect { cell ->
							step[0]++
							if (step[0] % 10 == 0) {
								updateProgress(step[0], totalSteps)
								updateMessage(String.format("Processing cell %d of %d (%.0f%%)...", step[0], totalSteps, (step[0] / (double) totalSteps) * 100))
							}
							def coord = cellCoordinates[cell]
							def localNeighborhood = cellCoordinates.findAll { c, xy ->
								def dx = xy[0] - coord[0]
								def dy = xy[1] - coord[1]
								(dx*dx + dy*dy) <= radiusSq
							}.keySet()
							if (localNeighborhood.isEmpty()) return null
							def avg = selectedMarkers.indices.collect { i ->
								localNeighborhood.collect { n -> cellMarkerMap[n][i] }.sum() / localNeighborhood.size()
							}
							[cell, new EuclideanDistance().compute(avgVec as double[], avg as double[])]
						}.findAll { it != null }
						distances.sort { it[1] }
						finalCells.addAll(distances.take(topN).collect { it[0] })
						return null
					}
				}

				progressBar.progressProperty().bind(task.progressProperty())
				progressLabel.textProperty().bind(task.messageProperty())

				task.setOnSucceeded(e -> {
					def greenClass = PathClass.fromString("Neighborhood-Green")
					finalCells.findAll { it != null }.each { it.setPathClass(greenClass) }
					hierarchy.getSelectionModel().setSelectedObjects([targetCell] + finalCells, targetCell)
					progressStage.close()
					long elapsed = System.currentTimeMillis() - startTime
					println "Neighborhood Search finished in ${elapsed / 1000.0} seconds."
				})

				Thread thread = new Thread(task)
				thread.setDaemon(true)
				thread.start()
				return
			}

			else if (markerSelected || morphSelected) {
				def features = []
				if (markerSelected) {
					markerCheckboxes.findAll { it.isSelected() }.each { cb -> features << "Cell: ${cb.getText()} mean" }
				}
				if (morphSelected) {
					if (cbArea.isSelected()) features << "Cell: Area"
					if (cbPerimeter.isSelected()) features << "Cell: Perimeter"
					if (cbCircularity.isSelected()) features << "Cell: Circularity"
					if (cbMaxCaliper.isSelected()) features << "Cell: Max caliper"
					if (cbMinCaliper.isSelected()) features << "Cell: Min caliper"
					if (cbEccentricity.isSelected()) features << "Cell: Eccentricity"
				}
				def targetVec = features.collect { targetCell.getMeasurementList().getMeasurementValue(it) ?: 0.0 }
				def distances = allCells.collect { cell ->
					def vec = features.collect { cell.getMeasurementList().getMeasurementValue(it) ?: 0.0 }
					[cell, new EuclideanDistance().compute(targetVec as double[], vec as double[])]
				}
				distances.sort { it[1] }
				finalCells = distances.take(topN).collect { it[0] }

			} else {
				finalCells = allCells.findAll { cell ->
					def dx = cell.getROI().getCentroidX() - centerX
					def dy = cell.getROI().getCentroidY() - centerY
					(dx*dx + dy*dy) <= radiusPixels * radiusPixels
				}
			}

			def greenClass = PathClass.fromString("Neighborhood-Green")
			finalCells.findAll { it != null }.each { it.setPathClass(greenClass) }
			hierarchy.getSelectionModel().setSelectedObjects([targetCell] + finalCells, targetCell)
			Platform.runLater { progressStage.close() }
			long elapsed = System.currentTimeMillis() - startTime
			println "Neighborhood Search finished in ${elapsed / 1000.0} seconds."
		}

		btnExport.setOnAction {
			def highlightedCells = hierarchy.getSelectionModel().getSelectedObjects().findAll { it.isCell() }
			if (highlightedCells.isEmpty()) {
				new Alert(Alert.AlertType.WARNING, "No highlighted cells to export.").show()
				return
			}
			def fileChooser = new FileChooser()
			fileChooser.setTitle("Save CSV")
			fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV Files", "*.csv"))
			def window = qupath.getViewer().getView().getScene().getWindow()
			File file = fileChooser.showSaveDialog(window)
			if (file == null) return
			file.withPrintWriter { pw ->
				pw.println("CentroidX,CentroidY")
				highlightedCells.each { cell ->
					def roi = cell.getROI()
					pw.println("${roi.getCentroidX()},${roi.getCentroidY()}")
				}
			}
			new Alert(Alert.AlertType.INFORMATION, "CSV exported successfully!").show()
		}

		btnReset.setOnAction {
			if (circleAnnotationRef != null) {
				hierarchy.removeObject(circleAnnotationRef, false)
				circleAnnotationRef = null
			}
			hierarchy.getSelectionModel().clearSelection()
			hierarchy.getDetectionObjects().findAll { it.isCell() }.each { it.setPathClass(null) }
			tfRadius.setText("50")
			tfTopN.setText("4000")
			markerCheckboxes.each { it.setSelected(false) }
			[cbArea, cbPerimeter, cbCircularity, cbMaxCaliper, cbMinCaliper, cbEccentricity].each { it.setSelected(false) }
			surroundCheckboxes.each { it.setSelected(false) }
		}

		HBox bottomRow = new HBox(10, radiusLabel, tfRadius, topNLabel, tfTopN, btnGo, btnReset, btnExport)
		bottomRow.setAlignment(Pos.CENTER_RIGHT)
		VBox dialogContent = new VBox(10, markerBox, morphBox, surroundBox, bottomRow)
		dialogContent.setStyle("-fx-padding: 20px; -fx-spacing: 15px;")

		Stage stage = new Stage()
		stage.setTitle("Neighborhood Search Options")
		stage.initOwner(qupath.getStage())
		stage.setScene(new Scene(dialogContent))
		stage.show()
	}


	private static void runMultiQuerySearch(QuPathGUI qupath) {
		def imageData = qupath.getImageData()
		if (imageData == null) {
			def alert = new Alert(AlertType.WARNING, "No image data available.")
			alert.initOwner(qupath.getStage())
			alert.show()

			return
		}
		def hierarchy = imageData.getHierarchy()

		// --- Build dynamic marker checkboxes (similar to Neighborhood search) ---
		def allDetections = hierarchy.getDetectionObjects().findAll { it.isCell() }
		if (allDetections.isEmpty()) {
			new Alert(AlertType.WARNING, "No cell detections found to extract marker names.").show()
			return
		}
		def measurementNames = allDetections[0].getMeasurementList().getMeasurementNames()
		def markerLabels = measurementNames.findAll { it.startsWith("Cell: ") && it.endsWith(" mean") }
		markerLabels = markerLabels.collect { it.replace("Cell: ", "").replace(" mean", "") }

		def markerCheckboxes = markerLabels.collect { new CheckBox(it) }
		CheckBox cbMarkerSelectAll = new CheckBox("Select All Markers")
		cbMarkerSelectAll.setOnAction {
			boolean value = cbMarkerSelectAll.isSelected()
			markerCheckboxes.each { it.setSelected(value) }
		}
		VBox markerBox = new VBox(5, new Label("Marker Selections:"), cbMarkerSelectAll)
		// Partition marker checkboxes into columns (4 columns)
		def partitionCheckboxes = { List<CheckBox> checkboxes, int numColumns ->
			int itemsPerColumn = (int) Math.ceil(checkboxes.size() / (double) numColumns)
			def columns = []
			for (int i = 0; i < numColumns; i++) {
				int start = i * itemsPerColumn
				int end = Math.min(start + itemsPerColumn, checkboxes.size())
				columns << new VBox(5, *checkboxes.subList(start, end))
			}
			return new HBox(10, *columns)
		}
		markerBox.getChildren().add(partitionCheckboxes(markerCheckboxes, 4))

		// --- Morphological feature checkboxes ---
		CheckBox cbArea = new CheckBox("Area")
		CheckBox cbPerimeter = new CheckBox("Perimeter")
		CheckBox cbCircularity = new CheckBox("Circularity")
		CheckBox cbMaxCaliper = new CheckBox("Max caliper")
		CheckBox cbMinCaliper = new CheckBox("Min caliper")
		CheckBox cbEccentricity = new CheckBox("Eccentricity")
		CheckBox cbMorphSelectAll = new CheckBox("Select All Morphological")
		cbMorphSelectAll.setOnAction {
			boolean v = cbMorphSelectAll.isSelected()
			[cbArea, cbPerimeter, cbCircularity, cbMaxCaliper, cbMinCaliper, cbEccentricity].each { it.setSelected(v) }
		}
		HBox morphCols = new HBox(10,
				new VBox(5, cbArea, cbPerimeter),
				new VBox(5, cbCircularity, cbMaxCaliper),
				new VBox(5, cbMinCaliper, cbEccentricity)
		)
		VBox morphBox = new VBox(5, new Label("Morphological Features:"), cbMorphSelectAll, morphCols)

		// --- Multi-query operation checkboxes (only one can be selected) ---
		def cbUnion = new CheckBox("Union Search ")
		def cbIntersection = new CheckBox("Intersection Search ")
		def cbSubtract = new CheckBox("Set Difference ")
		def cbContrastive = new CheckBox("Contrastive Search ")
		def enforceSingleOp = { changedCb ->
			[cbUnion, cbIntersection, cbSubtract, cbContrastive].each { cb ->
				if (cb != changedCb) { cb.setSelected(false) }
			}
		}
		[cbUnion, cbIntersection, cbSubtract, cbContrastive].each { cb ->
			cb.selectedProperty().addListener({ obs, old, newVal -> if (newVal) enforceSingleOp(cb) } as ChangeListener)
		}
		VBox opBox = new VBox(5, new Label("Multi-Query Operation:"), cbUnion, cbIntersection, cbSubtract, cbContrastive)

		// --- Top N field ---
		def tfTopN = new TextField("4000")
		HBox topNBox = new HBox(10, new Label("Top N:"), tfTopN)
		topNBox.setAlignment(Pos.CENTER_LEFT)

		// --- Buttons ---
		def btnRun = new Button("Run")
		def btnExport = new Button("Export CSV")
		def btnReset = new Button("Reset")
		def btnClose = new Button("Close")

		final Stage dialogStage = new Stage()

		// --- Define extraction method using dynamic selections ---
		def extractionMethod = { cell ->
			def vec = []
			if (markerCheckboxes.any { it.isSelected() }) {
				markerCheckboxes.findAll { it.isSelected() }.each { cb ->
					def value = cell.getMeasurementList().getMeasurementValue("Cell: " + cb.getText() + " mean") ?: 0.0
					vec << value
				}
			}
			def morphCbs = [cbArea, cbPerimeter, cbCircularity, cbMaxCaliper, cbMinCaliper, cbEccentricity]
			if (morphCbs.any { it.isSelected() }) {
				if (cbArea.isSelected()) { vec << (cell.getMeasurementList().getMeasurementValue("Cell: Area") ?: 0.0) }
				if (cbPerimeter.isSelected()) { vec << (cell.getMeasurementList().getMeasurementValue("Cell: Perimeter") ?: 0.0) }
				if (cbCircularity.isSelected()) { vec << (cell.getMeasurementList().getMeasurementValue("Cell: Circularity") ?: 0.0) }
				if (cbMaxCaliper.isSelected()) { vec << (cell.getMeasurementList().getMeasurementValue("Cell: Max caliper") ?: 0.0) }
				if (cbMinCaliper.isSelected()) { vec << (cell.getMeasurementList().getMeasurementValue("Cell: Min caliper") ?: 0.0) }
				if (cbEccentricity.isSelected()) { vec << (cell.getMeasurementList().getMeasurementValue("Cell: Eccentricity") ?: 0.0) }
			}
			if (vec.isEmpty()) {
				return [0.0] as double[]
			} else {
				return vec as double[]
			}
		}

		// --- Run button action ---
		btnRun.setOnAction {
			def selectedCells = hierarchy.getSelectionModel().getSelectedObjects().findAll { it.isCell() }

			if(selectedCells.size() > 20) {
				def alert = new Alert(AlertType.WARNING, "Too many cells selected (more than 20). Please select fewer cells to avoid memory issues.")
				alert.initOwner(qupath.getStage())
				alert.show()
				return
			}
			if (selectedCells.size() < 2) {
				def alert = new Alert(AlertType.WARNING, "Please select at least 2 cells for Multi-Query Search.").show()
				alert.initOwner(qupath.getStage())
				alert.show()

				return
			}
			int limit
			try {
				limit = tfTopN.getText().toInteger()
			} catch (Exception e) {
				def alert = new Alert(AlertType.WARNING, "Invalid Top N value.")
				alert.initOwner(qupath.getStage())
				alert.show()
				return
			}
			if (!(markerCheckboxes.any { it.isSelected() } || [cbArea, cbPerimeter, cbCircularity, cbMaxCaliper, cbMinCaliper, cbEccentricity].any { it.isSelected() })) {
				def alert = new Alert(AlertType.WARNING, "Please select at least one marker or morphological feature.")
				alert.initOwner(qupath.getStage())
				alert.show()
				return
			}
			def allCells = hierarchy.getDetectionObjects().findAll { it.isCell() }
			def similarSets = []
			selectedCells.eachWithIndex { cell, idx ->
				def targetVec = extractionMethod(cell)
				def distances = allCells.findAll { it != cell }.collect { other ->
					def otherVec = extractionMethod(other)
					[other, new EuclideanDistance().compute(targetVec, otherVec)]
				}
				distances.sort { it[1] }
				// For each query cell, take the top N similar cells
				def similar = distances.take(limit).collect { it[0] } as Set
				similarSets << similar
			}
			def finalResults=[]
			if (!(cbUnion.isSelected() || cbIntersection.isSelected() || cbSubtract.isSelected() || cbContrastive.isSelected())) {
				def alert = new Alert(AlertType.WARNING, "Please select a multi-query operation.")
				alert.initOwner(qupath.getStage())
				alert.show()
				return
			}
			if (cbContrastive.isSelected() && selectedCells.size() != 2) {
				def alert = new Alert(AlertType.WARNING, "Contrastive Search requires exactly 2 selected cells.")
				alert.initOwner(qupath.getStage())
				alert.show()

				return
			}
			if (cbUnion.isSelected()) {
				// For union, concatenate the lists (preserving duplicates)
				def unionList = []
				similarSets.each { unionList.addAll(it) }
				finalResults = unionList
			} else if (cbIntersection.isSelected()) {
				// For intersection, use set intersection (result ≤ 4000)
				def resultSet = similarSets[0] as Set
				similarSets[1..-1].each { resultSet.retainAll(it as Set) }
				finalResults = resultSet.toList().take(limit)
			} else if (cbSubtract.isSelected()) {
				def resultSet = similarSets[0] as Set
				similarSets[1..-1].each { resultSet.removeAll(it as Set) }
				finalResults = resultSet.toList().take(limit)
			} else if (cbContrastive.isSelected()) {
				def resultSet = similarSets[0] as Set
				resultSet.removeAll(similarSets[1] as Set)
				finalResults = resultSet.toList().take(limit)
			}
			// Use the classification from the extension (PathClassFactory)
			def multiQueryClass = PathClass.fromString("Multi-Query-Green")
			finalResults.each { it.setPathClass(multiQueryClass) }
			hierarchy.getSelectionModel().clearSelection()
			hierarchy.getSelectionModel().setSelectedObjects(finalResults as Collection<? extends PathObject>, null)
			println "Multi-Query Search complete. Selected ${finalResults.size()} cells."
		}

		btnExport.setOnAction {
			def selected = hierarchy.getSelectionModel().getSelectedObjects().findAll { it.isCell() }
			if (selected.isEmpty()) {
				def alert = new Alert(AlertType.WARNING, "No cells selected to export.")
				alert.initOwner(qupath.getStage())
				alert.show()

				return
			}
			FileChooser fileChooser = new FileChooser()
			fileChooser.setTitle("Export CSV")
			fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV Files", "*.csv"))
			def file = fileChooser.showSaveDialog(dialogStage)
			if (file) {
				file.withPrintWriter { pw ->
					pw.println("CentroidX,CentroidY")
					selected.each {
						def roi = it.getROI()
						pw.println("${roi.getCentroidX()},${roi.getCentroidY()}")
					}
				}
				def alert = new Alert(Alert.AlertType.INFORMATION, "Exported ${selected.size()} cells.")
				alert.initOwner(qupath.getStage())
				alert.show()

			}
		}

		btnReset.setOnAction {
			hierarchy.getDetectionObjects().findAll { it.isCell() }.each { it.setPathClass(null) }
			hierarchy.getSelectionModel().clearSelection()
		}

		btnClose.setOnAction { dialogStage.close() }

		TitledPane tpBasis = new TitledPane("Marker Selections", markerBox)
		tpBasis.setCollapsible(false)
		TitledPane tpMorph = new TitledPane("Morphological Features", morphBox)
		tpMorph.setCollapsible(false)
		TitledPane tpOps = new TitledPane("Multi-Query Operation", opBox)
		tpOps.setCollapsible(false)
		VBox layout = new VBox(10, tpBasis, tpMorph, tpOps, topNBox, new HBox(10, btnRun, btnExport, btnReset, btnClose))
		layout.setPadding(new Insets(20))

		dialogStage.setTitle("Multi-Query Search")
		dialogStage.initOwner(qupath.getStage())
		dialogStage.setScene(new Scene(layout))
		dialogStage.show()
	}

	private static final Logger logger = Logger.getLogger(DemoGroovyExtension.class.getName())

	// --- CSV-BASED CLUSTER SEARCH ---
	static void runCSVClusterSearch(QuPathGUI qupath) {
		Stage stage = new Stage()
		stage.setTitle("CSV Cluster Search")
		stage.initOwner(qupath.getStage()) // No modality!


		// UI Elements
		TextField filePathField = new TextField(); filePathField.setEditable(false)
		ComboBox<String> comboBox = new ComboBox<>()
		comboBox.getItems().addAll("level_1", "level_2", "level_3", "level_4", "level_5", "level_6")
		comboBox.setValue("level_1")

		Slider toleranceSlider = new Slider(1, 50, 10)
		toleranceSlider.setShowTickLabels(true); toleranceSlider.setShowTickMarks(true)
		toleranceSlider.setMajorTickUnit(10); toleranceSlider.setMinorTickCount(4)

		Button browseButton = new Button("Browse CSV")
		Button runButton = new Button("Run")
		runButton.setDisable(true)
		Button resetButton = new Button("Reset Highlights")

		// Layout
		GridPane grid = new GridPane()
		grid.setPadding(new Insets(20))
		grid.setHgap(10); grid.setVgap(10)

		grid.add(new Label("CSV File:"), 0, 0)
		grid.add(filePathField, 1, 0)
		grid.add(browseButton, 2, 0)

		grid.add(new Label("Cluster Level:"), 0, 1)
		grid.add(comboBox, 1, 1)

		grid.add(new Label("Tolerance (px):"), 0, 2)
		grid.add(toleranceSlider, 1, 2, 2, 1)

		grid.add(runButton, 1, 3)
		grid.add(resetButton, 2, 3)

		Scene scene = new Scene(grid)
		stage.setScene(scene)
		stage.show()

		// Internal data cache
		def rows = []
		def header = []
		File csvFile = null

		// Browse Action
		browseButton.setOnAction({
			FileChooser fileChooser = new FileChooser()
			fileChooser.setTitle("Select CSV File")
			fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV Files", "*.csv"))
			def selected = fileChooser.showOpenDialog(qupath.getStage())
			if (selected != null) {
				filePathField.setText(selected.getAbsolutePath())
				csvFile = selected
				rows.clear()
				header.clear()
				csvFile.withReader { reader ->
					def lines = reader.readLines()
					if (!lines) return
					header.addAll(lines[0].split(","))
					lines[1..-1].each { line ->
						def parts = line.split(",")
						def row = [:]
						for (int i = 0; i < header.size(); i++)
							row[header[i]] = (i < parts.length ? parts[i] : "")
						rows << row
					}
				}
				runButton.setDisable(false)
			}
		})

		resetButton.setOnAction({
			def imageData = qupath.getImageData()
			if (imageData != null) {
				def allCells = imageData.getHierarchy().getDetectionObjects().findAll { it.isCell() }
				allCells.each { it.setPathClass(null) }
				Platform.runLater {
					qupath.getViewer().repaint()

					def alert = new Alert(AlertType.INFORMATION, "✅ Highlights reset.")
					alert.initOwner(qupath.getStage())  // Tie alert to main window
					alert.showAndWait()
				}
			}
		})

		// Run Button Action
		runButton.setOnAction({
			if (!csvFile || rows.isEmpty()) return

			def imageData = qupath.getImageData()
			if (imageData == null) {
				def alert = new Alert(AlertType.WARNING, "⚠️ No image data found.")
				alert.initOwner(qupath.getStage())
				alert.showAndWait()
				return
			}

			def hierarchy = imageData.getHierarchy()
			def selected = hierarchy.getSelectionModel().getSelectedObjects().findAll { it.isCell() }
			if (selected.isEmpty()) {
				def alert = new Alert(AlertType.WARNING, "⚠️ Please select a cell to run cluster search.")
				alert.initOwner(qupath.getStage())
				alert.showAndWait()
				return
			}

			def chosenLevel = comboBox.getValue()
			def tolerance = toleranceSlider.getValue()
			def target = selected[0]
			def tx = target.getROI().getCentroidX()
			def ty = target.getROI().getCentroidY()

			// Find closest row
			def closestRow = rows.min { row ->
				if (!row.x || !row.y) return Double.MAX_VALUE
				def dx = (row.x as double) - tx
				def dy = (row.y as double) - ty
				return dx*dx + dy*dy
			}

			if (!closestRow) {
				def alert =new Alert(AlertType.WARNING, "No match found in CSV.")
				alert.initOwner(qupath.getStage())
				alert.showAndWait()
				return
			}

			def clusterLabel = closestRow[chosenLevel]
			def matchingRows = rows.findAll { it[chosenLevel] == clusterLabel }

			// Spatial bin map
			def binSize = tolerance
			def allCells = hierarchy.getDetectionObjects().findAll { it.isCell() }
			def cellMap = [:].withDefault { [] }
			allCells.each {
				def x = it.getROI().getCentroidX()
				def y = it.getROI().getCentroidY()
				def key = "${(int)(x/binSize)}_${(int)(y/binSize)}"
				cellMap[key] << it
			}

			def matchedCells = [] as Set
			matchingRows.each { row ->
				if (row.x && row.y) {
					def cx = row.x as double
					def cy = row.y as double
					def gx = (int)(cx / binSize)
					def gy = (int)(cy / binSize)
					for (dx in -1..1) {
						for (dy in -1..1) {
							def key = "${gx + dx}_${gy + dy}"
							def group = cellMap[key]
							group.each {
								def dx2 = it.getROI().getCentroidX() - cx
								def dy2 = it.getROI().getCentroidY() - cy
								if ((dx2 * dx2 + dy2 * dy2) <= (tolerance * tolerance))
									matchedCells << it
							}
						}
					}
				}
			}

			def pathClass = PathClass.fromString("Cluster-${chosenLevel}-${clusterLabel}")
			matchedCells.each { it.setPathClass(pathClass) }

			hierarchy.getSelectionModel().clearSelection()
			hierarchy.getSelectionModel().setSelectedObjects(matchedCells.toList(), null)

			Platform.runLater {
				qupath.getViewer().repaint()
				def alert = new Alert(AlertType.INFORMATION,
						"✅ Cluster highlight complete for ${chosenLevel} = ${clusterLabel}\nFound ${matchedCells.size()} cells"
				)
				alert.initOwner(qupath.getStage())  // Ensure it stays in the same QuPath window
				alert.showAndWait()
			}


			// Export matched
			def exportFile = new File(csvFile.getParent(), "matched_cells_${chosenLevel}_${clusterLabel}.csv")
			exportFile.withWriter { w ->
				w.write("CellX,CellY\n")
				matchedCells.each {
					def roi = it.getROI()
					w.write("${roi.getCentroidX()},${roi.getCentroidY()}\n")
				}
			}
			println "Exported to: ${exportFile.absolutePath}"
		})
	}


	static void runPhenotypeFinder(QuPathGUI qupath) {
		File selectedCSV = null
		List<Map<String, String>> cachedRows = null
		def makeCoordKey = { double x, double y -> "${Math.round(x)}_${Math.round(y)}" }

		def phenotypeColors = [
				"Leukocytes": new Color(255, 0, 0),
				"B_cells": new Color(0, 128, 255),
				"Myeloid_cells": new Color(255, 165, 0),
				"Lymphocytes": new Color(0, 255, 0),
				"Helper_T_cells": new Color(255, 20, 147),
				"Helper_T_foxp3_cells": new Color(186, 85, 211),
				"Helper_T_GZMB_cells": new Color(0, 139, 139),
				"Cytotoxic_T_cells": new Color(255, 69, 0),
				"Cytotoxic_T_Foxp3_cells": new Color(199, 21, 133),
				"NK": new Color(128, 0, 128),
				"Type1": new Color(0, 206, 209),
				"Dentric cells": new Color(70, 130, 180),
				"M1 macrophages": new Color(255, 140, 0),
				"M2 macrophages": new Color(139, 69, 19),
				"Regulatory T cells": new Color(127, 255, 212),
				"Memory T cells": new Color(173, 255, 47),
				"Stromal COLA1": new Color(154, 205, 50),
				"Stromal CD31": new Color(0, 191, 255),
				"Stromal aSMA": new Color(221, 160, 221),
				"Stromal FAP": new Color(147, 112, 219),
				"Epithelial": new Color(255, 215, 0),
				"Proliferation": new Color(0, 255, 127)
		]

		def imageData = qupath.getImageData()
		if (!imageData) {
			def alert =new Alert(AlertType.ERROR, "❌ No image open in QuPath.")
			alert.initOwner(qupath.getStage())
			alert.show()
			return
		}

		// --- First dialog to upload CSV ---
		Stage uploadStage = new Stage()
		uploadStage.setTitle("Upload Phenotype CSV")
		uploadStage.initModality(Modality.NONE)
		uploadStage.initOwner(qupath.getStage())

		TextField pathField = new TextField(); pathField.setEditable(false)
		Button browseButton = new Button("Browse")
		Button runUploadButton = new Button("Run")
		Button cancelUploadButton = new Button("Cancel")

		HBox fileRow = new HBox(10, pathField, browseButton)
		HBox buttonRow = new HBox(10, runUploadButton, cancelUploadButton)
		VBox layout = new VBox(10, fileRow, buttonRow)
		layout.setPadding(new Insets(20))

		uploadStage.setScene(new Scene(layout))
		uploadStage.show()

		FileChooser chooser = new FileChooser()
		chooser.setTitle("Select CSV File")
		chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV files", "*.csv"))

		browseButton.setOnAction {
			File file = chooser.showOpenDialog(qupath.getStage())
			if (file != null) {
				selectedCSV = file
				pathField.setText(file.getAbsolutePath())
			}
		}

		cancelUploadButton.setOnAction {
			uploadStage.close()
		}

		runUploadButton.setOnAction {
			if (selectedCSV == null || !selectedCSV.exists()) {
				def alert =new Alert(AlertType.WARNING, "Please select a valid CSV file.")
				alert.initOwner(qupath.getStage())
				alert.show()
				return
			}
			cachedRows = []
			selectedCSV.withReader { reader ->
				def lines = reader.readLines()
				def headers = lines[0].split(",").collect { it.trim() }
				lines[1..-1].each { line ->
					def parts = line.split(",")
					def row = [:]
					headers.eachWithIndex { h, i ->
						row[h] = (i < parts.size()) ? parts[i].trim() : ""
					}
					cachedRows << row
				}
			}
			uploadStage.close()
			showPhenotypeDialog(qupath, imageData, cachedRows, makeCoordKey, phenotypeColors)
		}
	}

	static void showPhenotypeDialog(QuPathGUI qupath, def imageData, List<Map<String, String>> cachedRows, Closure makeCoordKey, Map<String, Color> phenotypeColors) {
		Stage stage = new Stage()
		stage.setTitle("Select Phenotype")
		stage.initModality(Modality.NONE)
		stage.initOwner(qupath.getStage())

		ComboBox<String> phenotypeCombo = new ComboBox<>()
		phenotypeCombo.getItems().addAll(phenotypeColors.keySet().toList().sort())
		phenotypeCombo.setValue("Epithelial")

		ComboBox<String> tumorCombo = new ComboBox<>()
		tumorCombo.getItems().addAll("Yes", "No", "Ignore")
		tumorCombo.setValue("Ignore")

		Button runButton = new Button("Run")
		Button cancelButton = new Button("Close")

		GridPane grid = new GridPane()
		grid.setHgap(10)
		grid.setVgap(10)
		grid.setPadding(new Insets(20))
		grid.add(new Label("Phenotype:"), 0, 0)
		grid.add(phenotypeCombo, 1, 0)
		grid.add(new Label("Tumor Filter:"), 0, 1)
		grid.add(tumorCombo, 1, 1)
		grid.add(runButton, 0, 2)
		grid.add(cancelButton, 1, 2)

		stage.setScene(new Scene(grid))
		stage.show()

		cancelButton.setOnAction {
			stage.close()
		}

		runButton.setOnAction {
			def phenotype = phenotypeCombo.getValue()
			def tumorFilter = tumorCombo.getValue()

			def filtered = cachedRows.findAll { row ->
				try {
					def matchPheno = row[phenotype] == "1" || row[phenotype] == "1.0"
					def matchTumor = true
					if (tumorFilter == "Yes")
						matchTumor = row["tumor"]?.toLowerCase() in ["true", "1"]
					else if (tumorFilter == "No")
						matchTumor = row["tumor"]?.toLowerCase() in ["false", "0"]
					return matchPheno && matchTumor
				} catch (Exception e) {
					return false
				}
			}

			def csvKeySet = filtered.collect {
				makeCoordKey(it["Converted X µm"] as double, it["Converted Y µm"] as double)
			}.toSet()

			def allCells = imageData.getHierarchy().getDetectionObjects().findAll { it.isCell() }
			def matched = allCells.findAll {
				def key = makeCoordKey(it.getROI().getCentroidX(), it.getROI().getCentroidY())
				csvKeySet.contains(key)
			}

			def color = phenotypeColors.get(phenotype, Color.RED)
			def pathClass = PathClass.fromString("Phenotype-${phenotype}")
			pathClass.setColor(color.getRed(), color.getGreen(), color.getBlue())

			matched.each { it.setPathClass(pathClass) }

			def hierarchy = imageData.getHierarchy()
			hierarchy.getSelectionModel().clearSelection()
			hierarchy.getSelectionModel().setSelectedObjects(matched, null)
			qupath.getViewer().repaint()

			def alert = new Alert(AlertType.INFORMATION,
					"✅ Highlighted ${matched.size()} cells for '${phenotype}' (Tumor: ${tumorFilter})")
			alert.initOwner(qupath.getStage())
			alert.show()  // Non-blocking
		}
	}

	// --- FEATURE EXTRACTION METHODS ---

	// Extract morphological features.
	private static double[] extractMorphologicalFeatures(PathObject cell) {
		def measurementList = cell.getMeasurementList()
		double area         = measurementList.getMeasurementValue("Cell: Area")         ?: 0.0
		double perimeter    = measurementList.getMeasurementValue("Cell: Perimeter")    ?: 0.0
		double circularity  = measurementList.getMeasurementValue("Cell: Circularity")  ?: 0.0
		double maxCaliper   = measurementList.getMeasurementValue("Cell: Max caliper")  ?: 0.0
		double minCaliper   = measurementList.getMeasurementValue("Cell: Min caliper")  ?: 0.0
		double eccentricity = measurementList.getMeasurementValue("Cell: Eccentricity") ?: 0.0

		return [area, perimeter, circularity, maxCaliper, minCaliper, eccentricity] as double[]
	}

	// Extract marker-based features based on tissue type.
	private static double[] extractMarkerFeatures(PathObject cell) {
		def measurementList = cell.getMeasurementList()
		def markerChannels = measurementList.getMeasurementNames().findAll {
			it.startsWith("Cell: ") && it.endsWith(" mean")
		}
		def features = markerChannels.collect { channel ->
			measurementList.getMeasurementValue(channel) ?: 0.0
		}
		return features as double[]
	}

	// Combine morphological and marker features.
	private static double[] extractCombinedFeatures(PathObject cell) {
		double[] morph  = extractMorphologicalFeatures(cell)
		double[] marker = extractMarkerFeatures(cell)
		double[] combined = new double[morph.length + marker.length]
		System.arraycopy(morph, 0, combined, 0, morph.length)
		System.arraycopy(marker, 0, combined, morph.length, marker.length)
		return combined
	}
}
