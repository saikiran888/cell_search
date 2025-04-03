package qupath.ext.template

import javafx.beans.value.ChangeListener
import javafx.scene.control.*
import javafx.scene.control.Alert.AlertType
import javafx.util.Callback
import qupath.lib.common.Version
import qupath.lib.gui.QuPathGUI
import qupath.lib.gui.extensions.QuPathExtension
import qupath.lib.objects.PathObject
import qupath.lib.objects.classes.PathClassFactory
import org.apache.commons.math3.ml.distance.EuclideanDistance
import qupath.lib.roi.EllipseROI
import java.awt.Color  // Make sure this is at the top


import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.Properties
import java.net.URL
import java.sql.*
import java.util.logging.*
import javafx.application.Platform
import javafx.scene.control.Alert
import javafx.scene.control.ButtonType
import javafx.scene.control.ButtonBar.ButtonData
import javafx.geometry.Insets
import javafx.stage.FileChooser
import qupath.lib.objects.classes.PathClass
import qupath.lib.roi.ROIs
import qupath.lib.objects.PathAnnotationObject
import qupath.lib.measurements.MeasurementList
// Import AWT classes (using alias to avoid confusion with JavaFX Color)
import java.awt.Color as AwtColor
import java.awt.BasicStroke
// JavaFX UI layout classes
import javafx.scene.layout.*
import javafx.geometry.Pos
import javafx.scene.paint.Color as FxColor
import java.awt.Color
import java.net.URLClassLoader
import java.sql.SQLException
import java.sql.Statement
import java.io.File
import com.mysql.cj.jdbc.Driver
import qupath.lib.gui.QuPathGUI
import javafx.stage.FileChooser
import javafx.scene.control.*
import javafx.scene.layout.GridPane
import javafx.geometry.Insets
import javafx.application.Platform
import javafx.concurrent.Task
import javafx.scene.Scene
import javafx.scene.layout.VBox
import javafx.stage.Stage

import java.util.stream.Collectors
import groovy.transform.Field


import javafx.application.Platform
import javafx.stage.FileChooser
import javafx.scene.control.*
import javafx.scene.layout.*
import javafx.stage.Stage
import javafx.geometry.Insets
import javafx.scene.paint.Color
import javafx.scene.control.ColorPicker
import javafx.scene.control.Slider
import qupath.lib.objects.classes.PathClass
import qupath.lib.objects.classes.PathClassFactory
import java.io.BufferedWriter
import java.io.FileWriter
/**
 * A QuPath extension that demonstrates a 'Cell Search Engine' with:
 *  - Quick Search (Morphology, Marker, Combined, Neighborhood)
 *  - Comprehensive Search (Model Selection, MySQL, CSV-based clustering)
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

		def cellCountItem = new MenuItem("Cell Count")
		cellCountItem.setOnAction(e -> {
			runCellCount(qupath)
		})
		def multiQueryItem = new MenuItem("Multi-Query Search")
		multiQueryItem.setOnAction(e -> { runMultiQuerySearch(qupath) })
		quickSearchMenu.getItems().addAll(morphologyItem, markerItem, combinedItem, neighborhoodItem,multiQueryItem, cellCountItem)

		// --- COMPREHENSIVE SEARCH ---
		def comprehensiveMenu = new Menu("Comprehensive Search")

		def modelSelectionItem = new MenuItem("Model Selection (.h5)")
		modelSelectionItem.setOnAction(e -> {
			loadH5Model(qupath)
		})

		def sqliteSearchItem = new MenuItem("SQLite Cell Search")
		sqliteSearchItem.setOnAction(e -> {
			SQLiteCellSearch.runCellSearch(qupath)
		})
		comprehensiveMenu.getItems().add(sqliteSearchItem)

		def csvClusterItem = new MenuItem("Community Search")
		csvClusterItem.setOnAction(e -> runCSVClusterSearch(qupath))
		comprehensiveMenu.getItems().add(csvClusterItem)

		mainMenu.getItems().addAll(quickSearchMenu, comprehensiveMenu)
	}





	private static void runQuickSearch(QuPathGUI qupath, String searchType) {
		def imageData = qupath.getImageData()
		if (imageData == null) {
			new Alert(AlertType.WARNING, "No image data available.").showAndWait()
			return
		}
		def hierarchy = imageData.getHierarchy()
		def selectedCells = hierarchy.getSelectionModel().getSelectedObjects().findAll { it.isCell() }
		if (selectedCells.isEmpty()) {
			new Alert(AlertType.WARNING, "Please select a single cell before running the search!").showAndWait()
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

		def redClass = PathClassFactory.getPathClass("Highlighted-Red")
		allSelected.each { it.setPathClass(redClass) }

		def selectionModel = hierarchy.getSelectionModel()
		selectionModel.clearSelection()
		selectionModel.setSelectedObjects(allSelected, targetCell)

		println "Quick search '${searchType}' complete. Highlighted ${topCells.size()} similar cells."
	}

	private static void runCellCount(QuPathGUI qupath) {
		def imageData = qupath.getImageData()
		if (imageData == null) {
			new Alert(Alert.AlertType.WARNING, "No image data available.").showAndWait()
			return
		}
		def hierarchy = imageData.getHierarchy()
		def selectedCells = hierarchy.getSelectionModel().getSelectedObjects().findAll { it.isCell() }
		if (selectedCells.isEmpty()) {
			new Alert(Alert.AlertType.WARNING, "Please select a cell before running cell count!").showAndWait()
			return
		}

		// Get marker channels from the selected cell.
		PathObject exampleCell = selectedCells[0]
		List<String> channels = getMarkerChannels(exampleCell)

		// Count how many cells have >0 value for each channel.
		def allCells = hierarchy.getDetectionObjects().findAll { it.isCell() }
		def counts = [:]
		channels.each { channel ->
			counts[channel] = allCells.count { cell ->
				def value = cell.getMeasurementList().getMeasurementValue(channel) ?: 0.0
				return value > 0
			}
		}

		def message = "Cell Counts per Channel:\n"
		channels.each { channel ->
			message += "${channel}: ${counts[channel]}\n"
		}
		new Alert(Alert.AlertType.INFORMATION, message).showAndWait()
	}

	private static void runNeighborhoodSearch(QuPathGUI qupath) {
		def imageData = qupath.getImageData()
		if (imageData == null) {
			new Alert(Alert.AlertType.WARNING, "No image data available.").show()
			return
		}

		def hierarchy = imageData.getHierarchy()
		def allDetections = hierarchy.getDetectionObjects().findAll { it.isCell() }
		if (allDetections.isEmpty()) {
			new Alert(Alert.AlertType.WARNING, "No cell detections found to extract marker names.").show()
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
		Label markerLabel = new Label("Marker Selections:")
		markerLabel.setStyle("-fx-font-weight: bold;")
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
		def markerHBox = partitionCheckboxes(markerCheckboxes, 4)
		VBox markerBox = new VBox(5, markerLabel, cbMarkerSelectAll, markerHBox)

		// Morphological checkboxes
		CheckBox cbArea = new CheckBox("Area")
		CheckBox cbPerimeter = new CheckBox("Perimeter")
		CheckBox cbCircularity = new CheckBox("Circularity")
		CheckBox cbMaxCaliper = new CheckBox("Max caliper")
		CheckBox cbMinCaliper = new CheckBox("Min caliper")
		CheckBox cbEccentricity = new CheckBox("Eccentricity")
		VBox morphCol1 = new VBox(5, cbArea, cbPerimeter)
		VBox morphCol2 = new VBox(5, cbCircularity, cbMaxCaliper)
		VBox morphCol3 = new VBox(5, cbMinCaliper, cbEccentricity)
		HBox morphHBox = new HBox(10, morphCol1, morphCol2, morphCol3)
		CheckBox cbMorphSelectAll = new CheckBox("Select All")
		cbMorphSelectAll.setOnAction {
			boolean value = cbMorphSelectAll.isSelected()
			[cbArea, cbPerimeter, cbCircularity, cbMaxCaliper, cbMinCaliper, cbEccentricity].each { it.setSelected(value) }
		}
		Label morphLabel = new Label("Morphological Features:")
		morphLabel.setStyle("-fx-font-weight: bold;")
		VBox morphBox = new VBox(5, morphLabel, cbMorphSelectAll, morphHBox)

		// Surround markers
		def surroundCheckboxes = markerLabels.collect { new CheckBox(it) }
		CheckBox cbSurroundSelectAll = new CheckBox("Select All")
		cbSurroundSelectAll.setOnAction {
			boolean value = cbSurroundSelectAll.isSelected()
			surroundCheckboxes.each { it.setSelected(value) }
		}
		Label surroundLabel = new Label("Surround Markers:")
		surroundLabel.setStyle("-fx-font-weight: bold;")
		def surroundHBox = partitionCheckboxes(surroundCheckboxes, 4)
		VBox surroundBox = new VBox(5, surroundLabel, cbSurroundSelectAll, surroundHBox)

		// Radius / TopN fields
		Label topNLabel = new Label("Top N:")
		topNLabel.setStyle("-fx-font-weight: bold;")
		TextField tfTopN = new TextField("4000")
		Label radiusLabel = new Label("Radius (micrometers):")
		radiusLabel.setStyle("-fx-font-weight: bold;")
		TextField tfRadius = new TextField("50")
		Button btnGo = new Button("GO")
		Button btnReset = new Button("Reset")
		Button btnExport = new Button("Export CSV")

		def circleAnnotationRef = null

		// Export button logic
		btnExport.setOnAction {
			def highlightedCells = hierarchy.getSelectionModel().getSelectedObjects().findAll { it.isCell() }
			if (highlightedCells.isEmpty()) {
				new Alert(Alert.AlertType.WARNING, "No highlighted cells to export.").show()
				return
			}
			FileChooser fileChooser = new FileChooser()
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

		btnGo.setOnAction {
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
			def circleClass = PathClassFactory.getPathClass("Circle")
			if (circleClass == null)
				circleClass = new qupath.lib.objects.classes.PathClass("Circle", Color.RED, 2.0)
			circleAnnotationRef.setPathClass(circleClass)
			hierarchy.addObject(circleAnnotationRef, false)
			Platform.runLater { qupath.getViewer().repaint() }

			def allCells = hierarchy.getDetectionObjects().findAll { it.isCell() }
			def spatialCells = allCells.findAll { cell ->
				def c = cell.getROI()
				def dx = c.getCentroidX() - centerX
				def dy = c.getCentroidY() - centerY
				return (dx * dx + dy * dy) <= (radiusPixels * radiusPixels)
			}

			def finalCells = []
			boolean markerSelected = markerCheckboxes.any { it.isSelected() }
			boolean morphSelected = [cbArea, cbPerimeter, cbCircularity, cbMaxCaliper, cbMinCaliper, cbEccentricity].any { it.isSelected() }

			if (markerSelected && !morphSelected) {
				def selectedChannels = markerCheckboxes.findAll { it.isSelected() }*.getText().collect { "Cell: $it mean" }
				def targetVector = selectedChannels.collect { targetCell.getMeasurementList().getMeasurementValue(it) ?: 0.0 }
				def distances = allCells.collect { cell ->
					def vec = selectedChannels.collect { cell.getMeasurementList().getMeasurementValue(it) ?: 0.0 }
					[cell, new EuclideanDistance().compute(targetVector as double[], vec as double[])]
				}
				distances.sort { it[1] }
				finalCells = distances.take(topN).collect { it[0] }
			} else if (morphSelected && !markerSelected) {
				def selectedChannels = []
				if (cbArea.isSelected()) selectedChannels << "Cell: Area"
				if (cbPerimeter.isSelected()) selectedChannels << "Cell: Perimeter"
				if (cbCircularity.isSelected()) selectedChannels << "Cell: Circularity"
				if (cbMaxCaliper.isSelected()) selectedChannels << "Cell: Max caliper"
				if (cbMinCaliper.isSelected()) selectedChannels << "Cell: Min caliper"
				if (cbEccentricity.isSelected()) selectedChannels << "Cell: Eccentricity"
				def targetVector = selectedChannels.collect { targetCell.getMeasurementList().getMeasurementValue(it) ?: 0.0 }
				def distances = allCells.collect { cell ->
					def vec = selectedChannels.collect { cell.getMeasurementList().getMeasurementValue(it) ?: 0.0 }
					[cell, new EuclideanDistance().compute(targetVector as double[], vec as double[])]
				}
				distances.sort { it[1] }
				finalCells = distances.take(topN).collect { it[0] }
			} else if (markerSelected && morphSelected) {
				def insideCells = spatialCells
				if (!insideCells) {
					finalCells = spatialCells
				} else {
					def getCombinedVector = { cell ->
						def vec = []
						markerCheckboxes.each {
							if (it.isSelected()) vec << (cell.getMeasurementList().getMeasurementValue("Cell: ${it.getText()} mean") ?: 0.0)
						}
						if (cbArea.isSelected()) vec << (cell.getMeasurementList().getMeasurementValue("Cell: Area") ?: 0.0)
						if (cbPerimeter.isSelected()) vec << (cell.getMeasurementList().getMeasurementValue("Cell: Perimeter") ?: 0.0)
						if (cbCircularity.isSelected()) vec << (cell.getMeasurementList().getMeasurementValue("Cell: Circularity") ?: 0.0)
						if (cbMaxCaliper.isSelected()) vec << (cell.getMeasurementList().getMeasurementValue("Cell: Max caliper") ?: 0.0)
						if (cbMinCaliper.isSelected()) vec << (cell.getMeasurementList().getMeasurementValue("Cell: Min caliper") ?: 0.0)
						if (cbEccentricity.isSelected()) vec << (cell.getMeasurementList().getMeasurementValue("Cell: Eccentricity") ?: 0.0)
						return vec as double[]
					}
					def avgVector = insideCells.collect(getCombinedVector).transpose().collect { it.sum() / it.size() }
					def outsideCells = allCells - insideCells
					def distances = outsideCells.collect { cell ->
						def vec = getCombinedVector(cell)
						[cell, new EuclideanDistance().compute(avgVector as double[], vec as double[])]
					}
					distances.sort { it[1] }
					finalCells = distances.take(topN).collect { it[0] }
				}
			} else {
				finalCells = spatialCells
			}

			def greenClass = PathClassFactory.getPathClass("Neighborhood-Green")
			finalCells.each { it.setPathClass(greenClass) }
			hierarchy.getSelectionModel().setSelectedObjects([targetCell] + finalCells, targetCell)
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
			new Alert(Alert.AlertType.WARNING, "No image data available.").show()
			return
		}
		def hierarchy = imageData.getHierarchy()
		def selectedCells = hierarchy.getSelectionModel().getSelectedObjects().findAll { it.isCell() }
		if (selectedCells.size() < 2) {
			new Alert(Alert.AlertType.WARNING, "Please select at least 2 cells for Multi-Query Search.").show()
			return
		}

		// --- Build dynamic marker checkboxes (similar to Neighborhood search) ---
		def allDetections = hierarchy.getDetectionObjects().findAll { it.isCell() }
		if (allDetections.isEmpty()) {
			new Alert(Alert.AlertType.WARNING, "No cell detections found to extract marker names.").show()
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
		def cbSubtract = new CheckBox("Subtract Search ")
		def cbContrastive = new CheckBox("Contrastive Search ")

		// Enforce mutual exclusion for the operation checkboxes.
		def enforceSingleOp = { changedCb ->
			[cbUnion, cbIntersection, cbSubtract, cbContrastive].each { cb ->
				if (cb != changedCb) { cb.setSelected(false) }
			}
		}
		[cbUnion, cbIntersection, cbSubtract, cbContrastive].each { cb ->
			cb.selectedProperty().addListener({ obs, old, newVal -> if(newVal) enforceSingleOp(cb) } as ChangeListener)
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

		// Declare the stage variable early so it is available to all closures.
		final Stage dialogStage = new Stage()

		// --- Define extraction method using dynamic selections ---
		def extractionMethod = { cell ->
			def vec = []
			// Append marker values if any marker checkbox is selected:
			if (markerCheckboxes.any { it.isSelected() }) {
				markerCheckboxes.findAll { it.isSelected() }.each { cb ->
					def value = cell.getMeasurementList().getMeasurementValue("Cell: " + cb.getText() + " mean") ?: 0.0
					vec << value
				}
			}
			// Append morphological measurements if any morphological checkbox is selected:
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
				// If nothing is selected, default to an all-zero vector.
				return [0.0] as double[]
			} else {
				return vec as double[]
			}
		}

		// --- Run button action ---
		btnRun.setOnAction {
			int limit
			try {
				limit = tfTopN.getText().toInteger()
			} catch (Exception e) {
				new Alert(Alert.AlertType.WARNING, "Invalid Top N value.").show()
				return
			}
			// Ensure at least one marker or morphological feature is selected.
			if (!(markerCheckboxes.any { it.isSelected() } || [cbArea, cbPerimeter, cbCircularity, cbMaxCaliper, cbMinCaliper, cbEccentricity].any { it.isSelected() })) {
				new Alert(Alert.AlertType.WARNING, "Please select at least one marker or morphological feature.").show()
				return
			}
			def allCells = hierarchy.getDetectionObjects().findAll { it.isCell() }
			def similarSets = []
			selectedCells.each { cell ->
				def targetVec = extractionMethod(cell)
				def distances = allCells.findAll { it != cell }.collect { other ->
					def otherVec = extractionMethod(other)
					[other, new EuclideanDistance().compute(targetVec, otherVec)]
				}
				distances.sort { it[1] }
				// Take top N similar cells for each query.
				def similar = distances.take(limit).collect { it[0] } as Set
				similarSets << similar
			}
			// Ensure one operation is selected.
			if (!(cbUnion.isSelected() || cbIntersection.isSelected() || cbSubtract.isSelected() || cbContrastive.isSelected())) {
				new Alert(Alert.AlertType.WARNING, "Please select a multi-query operation.").show()
				return
			}
			// For Contrastive, require exactly 2 selected cells.
			if (cbContrastive.isSelected() && selectedCells.size() != 2) {
				new Alert(Alert.AlertType.WARNING, "Contrastive Search requires exactly 2 selected cells.").show()
				return
			}
			// Perform the set operation.
			def resultSet = [] as Set
			if (cbUnion.isSelected()) {
				similarSets.each { resultSet.addAll(it) }
			} else if (cbIntersection.isSelected()) {
				resultSet.addAll(similarSets[0])
				similarSets[1..-1].each { resultSet.retainAll(it) }
			} else if (cbSubtract.isSelected()) {
				resultSet.addAll(similarSets[0])
				similarSets[1..-1].each { resultSet.removeAll(it) }
			} else if (cbContrastive.isSelected()) {
				resultSet.addAll(similarSets[0])
				resultSet.removeAll(similarSets[1])
			}
			def finalResults = resultSet.toList().take(limit)
			def multiQueryClass = PathClassFactory.getPathClass("Multi-Query-Green")
			finalResults.each { it.setPathClass(multiQueryClass) }
			hierarchy.getSelectionModel().clearSelection()
			hierarchy.getSelectionModel().setSelectedObjects(finalResults, null)
			println "Multi-Query Search complete. Selected ${finalResults.size()} cells."
		}

		// --- Export CSV Action ---
		btnExport.setOnAction {
			def selected = hierarchy.getSelectionModel().getSelectedObjects().findAll { it.isCell() }
			if (selected.isEmpty()) {
				new Alert(Alert.AlertType.WARNING, "No cells selected to export.").show()
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
				new Alert(Alert.AlertType.INFORMATION, "Exported ${selected.size()} cells.").show()
			}
		}

		// --- Reset Action ---
		btnReset.setOnAction {
			hierarchy.getDetectionObjects().findAll { it.isCell() }.each { it.setPathClass(null) }
			hierarchy.getSelectionModel().clearSelection()
		}

		// --- Close Action ---
		btnClose.setOnAction { dialogStage.close() }

		// --- Layout the dialog ---
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

	// --- SQLITE-BASED CELL SEARCH ---
	class SQLiteCellSearch {
		private static final String DB_PATH = "D:/similarity_matrix.db"

		static void runCellSearch(QuPathGUI qupath) {
			new Thread({
				Connection conn = null
				PreparedStatement stmt = null
				ResultSet rs = null

				try {
					Class.forName("org.sqlite.JDBC")
					conn = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH)

					if (conn == null) {
						Platform.runLater {
							new Alert(AlertType.ERROR, "❌ Failed to connect to SQLite.").showAndWait()
						}
						return
					}

					def imageData = qupath.getImageData()
					if (imageData == null) {
						Platform.runLater {
							new Alert(AlertType.WARNING, "No image data available.").showAndWait()
						}
						return
					}

					def hierarchy = imageData.getHierarchy()
					def selectedCells = hierarchy.getSelectionModel().getSelectedObjects().findAll { it.isDetection() }
					if (selectedCells.isEmpty()) {
						Platform.runLater {
							new Alert(AlertType.WARNING, "Please select a single cell before searching!").showAndWait()
						}
						return
					}

					def targetCell = selectedCells[0]
					def roi = targetCell.getROI()
					double pixelSize = imageData.getServer().getPixelCalibration().getPixelWidthMicrons()
					double centroidX = roi.getCentroidX() * pixelSize
					double centroidY = roi.getCentroidY() * pixelSize

					println "📍 Selected Cell Centroid: X = ${centroidX}, Y = ${centroidY}"

					stmt = conn.prepareStatement("""
                        SELECT cell_index, x, y, 
                        ((x - ?) * (x - ?) + (y - ?) * (y - ?)) AS distance
                        FROM cell_coordinates
                        WHERE x BETWEEN ? AND ? AND y BETWEEN ? AND ?
                        ORDER BY distance ASC
                        LIMIT 1;
                    """)
					stmt.setDouble(1, centroidX)
					stmt.setDouble(2, centroidX)
					stmt.setDouble(3, centroidY)
					stmt.setDouble(4, centroidY)
					stmt.setDouble(5, centroidX - 15)
					stmt.setDouble(6, centroidX + 15)
					stmt.setDouble(7, centroidY - 15)
					stmt.setDouble(8, centroidY + 15)

					rs = stmt.executeQuery()
					if (!rs.next()) {
						println "⚠️ No matching cell found in `cell_coordinates`."
						Platform.runLater {
							new Alert(AlertType.WARNING,
									"⚠️ No matching cell found in SQLite!\n\n" +
											"Selected Cell Centroid:\n" +
											"X = ${centroidX} µm\n" +
											"Y = ${centroidY} µm"
							).showAndWait()
						}
						return
					}
					int cellIndex = rs.getInt("cell_index")
					double matchedX = rs.getDouble("x")
					double matchedY = rs.getDouble("y")

					println "✅ Matched Cell Index: ${cellIndex} (X = ${matchedX}, Y = ${matchedY} µm)"

					stmt = conn.prepareStatement("""
                        SELECT ts.similar_cell_index, c.x, c.y
                        FROM top_similarities ts
                        JOIN cell_coordinates c ON ts.similar_cell_index = c.cell_index
                        WHERE ts.cell_index = ?
                        ORDER BY ts.similarity DESC
                        LIMIT 100;
                    """)
					stmt.setInt(1, cellIndex)
					rs = stmt.executeQuery()

					def similarCells = []
					while (rs.next()) {
						similarCells.add([
								"cell_index": rs.getInt("similar_cell_index"),
								"x": rs.getDouble("x"),
								"y": rs.getDouble("y")
						])
					}

					String alertMessage = "📍 Selected Cell Centroid:\nX = ${centroidX} µm\nY = ${centroidY} µm\n\n"
					alertMessage += "✅ Matched Cell in DB: Index = ${cellIndex}, X = ${matchedX}, Y = ${matchedY} µm\n\n"

					if (similarCells.isEmpty()) {
						println "⚠️ No similar cells found for cell_index ${cellIndex}."
						alertMessage += "⚠️ No similar cells found for Cell Index ${cellIndex}."
						Platform.runLater {
							new Alert(AlertType.WARNING, alertMessage).showAndWait()
						}
						return
					}

					alertMessage += "✅ Found ${similarCells.size()} similar cells:\n"
					similarCells.eachWithIndex { cellData, i ->
						alertMessage += "🔹 Similar Cell ${i + 1}: Index = ${cellData["cell_index"]}, X = ${cellData["x"]} µm, Y = ${cellData["y"]} µm\n"
					}

					def redClass = PathClassFactory.getPathClass("Highlighted-Red")
					def allCells = hierarchy.getDetectionObjects().findAll { it.isDetection() }

					def matchingObjects = []
					similarCells.each { cellData ->
						def matchingCell = allCells.find {
							it.getMeasurementList().getMeasurementValue("Cell Index") as Integer == cellData["cell_index"] as Integer
						}
						if (matchingCell) {
							matchingCell.setPathClass(redClass)
							matchingObjects.add(matchingCell)
						}
					}

					def selectionModel = hierarchy.getSelectionModel()
					selectionModel.clearSelection()
					selectionModel.setSelectedObjects([targetCell] + matchingObjects, targetCell)

					Platform.runLater {
						new Alert(AlertType.INFORMATION, alertMessage).showAndWait()
					}

				} catch (ClassNotFoundException e) {
					Platform.runLater {
						new Alert(AlertType.ERROR, "❌ SQLite JDBC Driver Not Found. Ensure the JAR is included.").showAndWait()
					}
					e.printStackTrace()
				} catch (SQLException e) {
					Platform.runLater {
						new Alert(AlertType.ERROR, "❌ SQL Error: ${e.message}").showAndWait()
					}
					e.printStackTrace()
				} finally {
					try {
						if (rs != null) rs.close()
						if (stmt != null) stmt.close()
						if (conn != null) conn.close()
					} catch (SQLException e) {
						Platform.runLater {
							new Alert(AlertType.WARNING, "⚠️ Failed to close SQLite resources: ${e.message}").showAndWait()
						}
					}
				}
			}).start()
		}
	}

	// --- CSV-BASED CLUSTER SEARCH ---
	static void runCSVClusterSearch(QuPathGUI qupath) {
		Stage stage = new Stage()
		stage.setTitle("CSV Cluster Search")

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
		stage.initOwner(qupath.getStage())
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

		// Reset Action
		resetButton.setOnAction({
			def imageData = qupath.getImageData()
			if (imageData != null) {
				def allCells = imageData.getHierarchy().getDetectionObjects().findAll { it.isCell() }
				allCells.each { it.setPathClass(null) }
				Platform.runLater {
					qupath.getViewer().repaint()
					new Alert(Alert.AlertType.INFORMATION, "Highlights reset.").showAndWait()
				}
			}
		})

		// Run Button Action
		runButton.setOnAction({
			if (!csvFile || rows.isEmpty()) return

			def imageData = qupath.getImageData()
			if (imageData == null) {
				new Alert(Alert.AlertType.WARNING, "No image data found.").showAndWait()
				return
			}

			def hierarchy = imageData.getHierarchy()
			def selected = hierarchy.getSelectionModel().getSelectedObjects().findAll { it.isCell() }
			if (selected.isEmpty()) {
				new Alert(Alert.AlertType.WARNING, "Please select a cell to run cluster search.").showAndWait()
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
				new Alert(Alert.AlertType.WARNING, "No match found in CSV.").showAndWait()
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

			def pathClass = PathClassFactory.getPathClass("Cluster-${chosenLevel}-${clusterLabel}")
			matchedCells.each { it.setPathClass(pathClass) }

			hierarchy.getSelectionModel().clearSelection()
			hierarchy.getSelectionModel().setSelectedObjects(matchedCells.toList(), null)

			Platform.runLater {
				qupath.getViewer().repaint()
				new Alert(Alert.AlertType.INFORMATION,
						"✅ Cluster highlight complete for ${chosenLevel} = ${clusterLabel}\nFound ${matchedCells.size()} cells"
				).showAndWait()
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
