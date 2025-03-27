package qupath.ext.template

import javafx.scene.control.*
import javafx.scene.control.Alert.AlertType
import qupath.lib.common.Version
import qupath.lib.gui.QuPathGUI
import qupath.lib.gui.extensions.QuPathExtension
import qupath.lib.objects.PathObject
import qupath.lib.objects.classes.PathClassFactory
import org.apache.commons.math3.ml.distance.EuclideanDistance
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
import javafx.scene.control.Alert.AlertType








import javafx.geometry.Pos
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import javafx.scene.control.*
import javafx.application.Platform
import qupath.lib.roi.ROIs
import qupath.lib.objects.PathAnnotationObject
import qupath.lib.objects.classes.PathClassFactory
import org.apache.commons.math3.ml.distance.EuclideanDistance
import java.awt.Color
import java.net.URLClassLoader
import java.sql.SQLException
import java.sql.Statement
import java.io.File
import javafx.application.Platform
import com.mysql.cj.jdbc.Driver

/**
 * A QuPath extension that demonstrates a 'Cell Search Engine' with:
 *  - Quick Search (Morphology, Markers, Combined, Neighborhood)
 *  - Comprehensive Search (Model Selection, MySQL)
 */
class DemoGroovyExtension implements QuPathExtension {

	String name = "Cell Search Engine"
	String description = "Offers quick and comprehensive cell similarity searches."
	Version QuPathVersion = Version.parse("v0.4.0")

	@Override
	void installExtension(QuPathGUI qupath) {
		// Create a top-level menu for the extension
		def mainMenu = qupath.getMenu("Extensions>" + name, true)

		// ------------------------------------------------------------
		// 1. QUICK CELL SEARCH
		// ------------------------------------------------------------
		def quickSearchMenu = new Menu("Quick Cell Search")

		// (A) Morphology-based search
		def morphologyItem = new MenuItem("Morphology-based Search")
		morphologyItem.setOnAction(e -> {
			runQuickSearch(qupath, "morphology")
		})

		// (B) Marker-based search
		def markerItem = new MenuItem("Marker-based Search")
		markerItem.setOnAction(e -> {
			runQuickSearch(qupath, "marker")
		})

		// (C) Combined (Morphology + Marker)
		def combinedItem = new MenuItem("Combined Search")
		combinedItem.setOnAction(e -> {
			runQuickSearch(qupath, "combined")
		})

		// (D) Similar Neighborhood Search
		def neighborhoodItem = new MenuItem("Similar Neighborhood Search")
		neighborhoodItem.setOnAction(e -> {
			runNeighborhoodSearch(qupath)
		})

		// Add these items to the Quick Search menu
		quickSearchMenu.getItems().addAll(morphologyItem, markerItem, combinedItem, neighborhoodItem)

		// ------------------------------------------------------------
		// 2. COMPREHENSIVE SEARCH
		// ------------------------------------------------------------
		def comprehensiveMenu = new Menu("Comprehensive Search")

		// (A) Model selection (loading .h5)
		def modelSelectionItem = new MenuItem("Model Selection (.h5)")
		modelSelectionItem.setOnAction(e -> {
			loadH5Model(qupath)
		})

		// (B) SQLite-based advanced search
		def sqliteSearchItem = new MenuItem("SQLite Cell Search")
		sqliteSearchItem.setOnAction(e -> {
			SQLiteCellSearch.runCellSearch(qupath)
		})
		comprehensiveMenu.getItems().add(sqliteSearchItem)

		// ------------------------------------------------------------
		// 3. CLUSTER-BASED CELL SEARCH VIA CSV LOOKUP
		// ------------------------------------------------------------

		def csvClusterItem = new MenuItem("CSV Cluster Search (level_0)")
		csvClusterItem.setOnAction(e -> runCSVClusterSearch(qupath))
		comprehensiveMenu.getItems().add(csvClusterItem)


		// ------------------------------------------------------------
		// ADD SUB-MENUS TO MAIN MENU
		// ------------------------------------------------------------
		mainMenu.getItems().addAll(quickSearchMenu, comprehensiveMenu)
	}

	// ----------------------------------------------------------------
	// QUICK SEARCH LOGIC
	// ----------------------------------------------------------------
	private static void runQuickSearch(QuPathGUI qupath, String searchType) {
		def imageData = qupath.getImageData()
		if (imageData == null) {
			new Alert(AlertType.WARNING, "No image data available.").showAndWait()
			return
		}

		def hierarchy = imageData.getHierarchy()
		// Get selected cell(s)
		def selectedCells = hierarchy.getSelectionModel().getSelectedObjects().findAll { it.isCell() }
		if (selectedCells.isEmpty()) {
			new Alert(AlertType.WARNING, "Please select a single cell before running the search!").showAndWait()
			return
		}

		def targetCell = selectedCells[0]
		println "Selected cell: ID = ${targetCell.getID()}"

		// Gather all cells in the image
		def cells = hierarchy.getDetectionObjects().findAll { it.isCell() }

		// Extract features for target
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
				// Fallback to marker if something unexpected
				targetFeatures = extractMarkerFeatures(targetCell)
		}

		// Calculate distances (Euclidean as an example) for each other cell
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

		// Sort by distance ascending (closest / most similar first)
		distances.sort { it[1] }

		// Pick top 5 for demonstration (or adjust to your needs)
		def topCells = distances.take(100).collect { it[0] }
		def allSelected = [targetCell] + topCells

		// Highlight them in red
		def redClass = PathClassFactory.getPathClass("Highlighted-Red")
		allSelected.each { it.setPathClass(redClass) }

		// Update QuPath selection
		def selectionModel = hierarchy.getSelectionModel()
		selectionModel.clearSelection()
		selectionModel.setSelectedObjects(allSelected, targetCell)

		println "Quick search '${searchType}' complete. Highlighted 5 most similar cells."
	}

	/**
	 * Neighborhood search example:
	 *  1. Filter cells within a certain radius of the target.
	 *  2. Possibly also check marker or morphology similarity.
	 */















	private static void runNeighborhoodSearch(QuPathGUI qupath) {
		def imageData = qupath.getImageData()
		if (imageData == null) {
			new Alert(Alert.AlertType.WARNING, "No image data available.").showAndWait()
			return
		}
		def hierarchy = imageData.getHierarchy()
		def selectedCells = hierarchy.getSelectionModel().getSelectedObjects().findAll { it.isCell() }
		if (selectedCells.isEmpty()) {
			new Alert(Alert.AlertType.WARNING, "Please select a single cell before running neighborhood search!").showAndWait()
			return
		}
		def targetCell = selectedCells[0]
		println "Neighborhood search for cell: ID = ${targetCell.getID()}"

		// --- Build the dialog ---

		// PART 1: Marker Selections – global marker-based search.
		CheckBox cbDAPI = new CheckBox("Cell: DAPI mean")
		CheckBox cbNeuN = new CheckBox("Cell: NeuN mean")
		CheckBox cbNFH = new CheckBox("Cell: NFH mean")
		CheckBox cbNFM = new CheckBox("Cell: NFM mean")
		VBox markerCol1 = new VBox(5, cbDAPI, cbNeuN, cbNFH, cbNFM)

		CheckBox cbMAP2 = new CheckBox("Cell: MAP2 mean")
		CheckBox cbSynaptophysin = new CheckBox("Cell: Synaptophysin mean")
		CheckBox cbCNPase = new CheckBox("Cell: CNPase mean")
		CheckBox cbNBP = new CheckBox("Cell: NBP mean")
		VBox markerCol2 = new VBox(5, cbMAP2, cbSynaptophysin, cbCNPase, cbNBP)

		HBox markerHBox = new HBox(10, markerCol1, markerCol2)
		// "Select All" for Marker Selections.
		CheckBox cbMarkerSelectAll = new CheckBox("Select All")
		cbMarkerSelectAll.setOnAction {
			boolean value = cbMarkerSelectAll.isSelected()
			[cbDAPI, cbNeuN, cbNFH, cbNFM, cbMAP2, cbSynaptophysin, cbCNPase, cbNBP].each { it.setSelected(value) }
		}
		Label markerLabel = new Label("Marker Selections:")
		markerLabel.setStyle("-fx-font-weight: bold;")
		VBox markerBox = new VBox(5, markerLabel, cbMarkerSelectAll, markerHBox)

		// PART 2: Morphological Features – 6 checkboxes in 2 columns (3 each).
		CheckBox cbArea = new CheckBox("Cell: Area")
		CheckBox cbPerimeter = new CheckBox("Cell: Perimeter")
		CheckBox cbCircularity = new CheckBox("Cell: Circularity")
		VBox morphCol1 = new VBox(5, cbArea, cbPerimeter, cbCircularity)

		CheckBox cbMaxCaliper = new CheckBox("Cell: Max caliper")
		CheckBox cbMinCaliper = new CheckBox("Cell: Min caliper")
		CheckBox cbEccentricity = new CheckBox("Cell: Eccentricity")
		VBox morphCol2 = new VBox(5, cbMaxCaliper, cbMinCaliper, cbEccentricity)

		HBox morphHBox = new HBox(10, morphCol1, morphCol2)
		// "Select All" for Morphological Features.
		CheckBox cbMorphSelectAll = new CheckBox("Select All")
		cbMorphSelectAll.setOnAction {
			boolean value = cbMorphSelectAll.isSelected()
			[cbArea, cbPerimeter, cbCircularity, cbMaxCaliper, cbMinCaliper, cbEccentricity].each { it.setSelected(value) }
		}
		Label morphLabel = new Label("Morphological Features:")
		morphLabel.setStyle("-fx-font-weight: bold;")
		VBox morphBox = new VBox(5, morphLabel, cbMorphSelectAll, morphHBox)

		// PART 3: Surround Markers – for computing average marker intensity.
		CheckBox cbSurroundDAPI = new CheckBox("Cell: DAPI mean")
		CheckBox cbSurroundNeuN = new CheckBox("Cell: NeuN mean")
		CheckBox cbSurroundNFH = new CheckBox("Cell: NFH mean")
		CheckBox cbSurroundNFM = new CheckBox("Cell: NFM mean")
		VBox surroundCol1 = new VBox(5, cbSurroundDAPI, cbSurroundNeuN, cbSurroundNFH, cbSurroundNFM)

		CheckBox cbSurroundMAP2 = new CheckBox("Cell: MAP2 mean")
		CheckBox cbSurroundSynaptophysin = new CheckBox("Cell: Synaptophysin mean")
		CheckBox cbSurroundCNPase = new CheckBox("Cell: CNPase mean")
		CheckBox cbSurroundNBP = new CheckBox("Cell: NBP mean")
		VBox surroundCol2 = new VBox(5, cbSurroundMAP2, cbSurroundSynaptophysin, cbSurroundCNPase, cbSurroundNBP)

		HBox surroundHBox = new HBox(10, surroundCol1, surroundCol2)
		// "Select All" for Surround Markers.
		CheckBox cbSurroundSelectAll = new CheckBox("Select All")
		cbSurroundSelectAll.setOnAction {
			boolean value = cbSurroundSelectAll.isSelected()
			[cbSurroundDAPI, cbSurroundNeuN, cbSurroundNFH, cbSurroundNFM, cbSurroundMAP2, cbSurroundSynaptophysin, cbSurroundCNPase, cbSurroundNBP].each { it.setSelected(value) }
		}
		Label surroundLabel = new Label("Surround Markers:")
		surroundLabel.setStyle("-fx-font-weight: bold;")
		VBox surroundBox = new VBox(5, surroundLabel, cbSurroundSelectAll, surroundHBox)

		// Additional parameter: Top N.
		Label topNLabel = new Label("Top N:")
		topNLabel.setStyle("-fx-font-weight: bold;")
		TextField tfTopN = new TextField("200")  // default value

		// Bottom row: Radius (in micrometers), Top N, GO button, and Reset button.
		Label radiusLabel = new Label("Radius (micrometers):")
		radiusLabel.setStyle("-fx-font-weight: bold;")
		TextField tfRadius = new TextField("50")  // default radius
		Button btnGo = new Button("GO")
		Button btnReset = new Button("Reset")
		HBox bottomRow = new HBox(10, radiusLabel, tfRadius, topNLabel, tfTopN, btnGo, btnReset)
		bottomRow.setAlignment(Pos.CENTER_RIGHT)

		// Put everything together in one main container (no custom background).
		VBox dialogContent = new VBox(10, markerBox, morphBox, surroundBox, bottomRow)
		dialogContent.setStyle("-fx-padding: 20px; -fx-spacing: 15px;")  // some spacing & padding

		Dialog<Void> dialog = new Dialog<>()
		dialog.setTitle("Neighborhood Search Options")
		dialog.setHeaderText("Configure neighborhood search parameters")
		dialog.getDialogPane().setContent(dialogContent)
		dialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL)
		// No background color is set, so it will use the default system/JavaFX theme.

		// We'll store the last added circle annotation so we can remove it on Reset.
		def circleAnnotationRef = null

		btnGo.setOnAction { event ->
			double radiusMicrons
			try {
				radiusMicrons = Double.parseDouble(tfRadius.getText())
			} catch (Exception e) {
				new Alert(Alert.AlertType.WARNING, "Invalid radius value.").showAndWait()
				return
			}
			int topN
			try {
				topN = Integer.parseInt(tfTopN.getText())
			} catch (Exception e) {
				new Alert(Alert.AlertType.WARNING, "Invalid Top N value.").showAndWait()
				return
			}

			// Get target cell's ROI and centroid.
			def roi = targetCell.getROI()
			double centerX = roi.getCentroidX()
			double centerY = roi.getCentroidY()
			double pixelSize = imageData.getServer().getPixelCalibration().getPixelWidthMicrons()
			double radiusPixels = radiusMicrons / pixelSize

			// Draw the circle ROI for visual reference.
			def circleROI = ROIs.createEllipseROI(
					centerX - radiusPixels,
					centerY - radiusPixels,
					2 * radiusPixels,
					2 * radiusPixels,
					roi.getImagePlane()
			)
			circleAnnotationRef = new PathAnnotationObject(circleROI)
			def circleClass = PathClassFactory.getPathClass("Circle")
			if (circleClass == null) {
				circleClass = new qupath.lib.objects.classes.PathClass("Circle", Color.RED, 2.0)
			}
			circleAnnotationRef.setPathClass(circleClass)
			hierarchy.addObject(circleAnnotationRef, false)
			Platform.runLater {
				qupath.getViewer().repaint()
			}

			// Get all cells.
			def allCells = hierarchy.getDetectionObjects().findAll { it.isCell() }

			// Spatial filtering: cells inside the circle.
			def spatialCells = allCells.findAll { cell ->
				double cellX = cell.getROI().getCentroidX()
				double cellY = cell.getROI().getCentroidY()
				double dx = cellX - centerX
				double dy = cellY - centerY
				return (dx * dx + dy * dy) <= (radiusPixels * radiusPixels)
			}

			// Decide filtering method:
			def finalCells = []  // final cells to highlight

			// Helper booleans.
			boolean markerSelected = cbDAPI.isSelected() || cbNeuN.isSelected() || cbNFH.isSelected() ||
					cbNFM.isSelected() || cbMAP2.isSelected() || cbSynaptophysin.isSelected() ||
					cbCNPase.isSelected() || cbNBP.isSelected()
			boolean morphSelected = cbArea.isSelected() || cbPerimeter.isSelected() || cbCircularity.isSelected() ||
					cbMaxCaliper.isSelected() || cbMinCaliper.isSelected() || cbEccentricity.isSelected()

			// Case 1: Only Marker Selections checked (global marker search, ignoring circle).
			if (markerSelected && !morphSelected) {
				def selectedChannels = []
				if (cbDAPI.isSelected()) { selectedChannels << "Cell: DAPI mean" }
				if (cbNeuN.isSelected()) { selectedChannels << "Cell: NeuN mean" }
				if (cbNFH.isSelected()) { selectedChannels << "Cell: NFH mean" }
				if (cbNFM.isSelected()) { selectedChannels << "Cell: NFM mean" }
				if (cbMAP2.isSelected()) { selectedChannels << "Cell: MAP2 mean" }
				if (cbSynaptophysin.isSelected()) { selectedChannels << "Cell: Synaptophysin mean" }
				if (cbCNPase.isSelected()) { selectedChannels << "Cell: CNPase mean" }
				if (cbNBP.isSelected()) { selectedChannels << "Cell: NBP mean" }

				def targetVector = []
				selectedChannels.each { channel ->
					targetVector << (targetCell.getMeasurementList().getMeasurementValue(channel) ?: 0.0)
				}
				def distances = allCells.collect { cell ->
					def cellVector = []
					selectedChannels.each { channel ->
						cellVector << (cell.getMeasurementList().getMeasurementValue(channel) ?: 0.0)
					}
					double d = new EuclideanDistance().compute(targetVector as double[], cellVector as double[])
					[cell, d]
				}
				distances.sort { it[1] }
				finalCells = distances.take(topN).collect { it[0] }
				println "Marker-only filtering applied: selected ${finalCells.size()} similar cells (global search)."

				// Case 2: Only Morphological Features checked.
			} else if (morphSelected && !markerSelected) {
				def selectedChannels = []
				if (cbArea.isSelected()) { selectedChannels << "Cell: Area" }
				if (cbPerimeter.isSelected()) { selectedChannels << "Cell: Perimeter" }
				if (cbCircularity.isSelected()) { selectedChannels << "Cell: Circularity" }
				if (cbMaxCaliper.isSelected()) { selectedChannels << "Cell: Max caliper" }
				if (cbMinCaliper.isSelected()) { selectedChannels << "Cell: Min caliper" }
				if (cbEccentricity.isSelected()) { selectedChannels << "Cell: Eccentricity" }

				def targetVector = []
				selectedChannels.each { channel ->
					targetVector << (targetCell.getMeasurementList().getMeasurementValue(channel) ?: 0.0)
				}
				def distances = allCells.collect { cell ->
					def cellVector = []
					selectedChannels.each { channel ->
						cellVector << (cell.getMeasurementList().getMeasurementValue(channel) ?: 0.0)
					}
					double d = new EuclideanDistance().compute(targetVector as double[], cellVector as double[])
					[cell, d]
				}
				distances.sort { it[1] }
				finalCells = distances.take(topN).collect { it[0] }
				println "Morphology-only filtering applied: selected ${finalCells.size()} similar cells (global search)."

				// Case 3: Combined filtering (both marker and morphological features selected).
			} else if (markerSelected && morphSelected) {
				def insideCells = spatialCells
				if (insideCells.isEmpty()) {
					println "No cells inside the circle for combined filtering; falling back to spatial filtering."
					finalCells = spatialCells
				} else {
					def getCombinedVector = { cell ->
						def vector = []
						// Marker features:
						if (cbDAPI.isSelected()) { vector << (cell.getMeasurementList().getMeasurementValue("Cell: DAPI mean") ?: 0.0) }
						if (cbNeuN.isSelected()) { vector << (cell.getMeasurementList().getMeasurementValue("Cell: NeuN mean") ?: 0.0) }
						if (cbNFH.isSelected()) { vector << (cell.getMeasurementList().getMeasurementValue("Cell: NFH mean") ?: 0.0) }
						if (cbNFM.isSelected()) { vector << (cell.getMeasurementList().getMeasurementValue("Cell: NFM mean") ?: 0.0) }
						if (cbMAP2.isSelected()) { vector << (cell.getMeasurementList().getMeasurementValue("Cell: MAP2 mean") ?: 0.0) }
						if (cbSynaptophysin.isSelected()) { vector << (cell.getMeasurementList().getMeasurementValue("Cell: Synaptophysin mean") ?: 0.0) }
						if (cbCNPase.isSelected()) { vector << (cell.getMeasurementList().getMeasurementValue("Cell: CNPase mean") ?: 0.0) }
						if (cbNBP.isSelected()) { vector << (cell.getMeasurementList().getMeasurementValue("Cell: NBP mean") ?: 0.0) }
						// Morphological features:
						if (cbArea.isSelected()) { vector << (cell.getMeasurementList().getMeasurementValue("Cell: Area") ?: 0.0) }
						if (cbPerimeter.isSelected()) { vector << (cell.getMeasurementList().getMeasurementValue("Cell: Perimeter") ?: 0.0) }
						if (cbCircularity.isSelected()) { vector << (cell.getMeasurementList().getMeasurementValue("Cell: Circularity") ?: 0.0) }
						if (cbMaxCaliper.isSelected()) { vector << (cell.getMeasurementList().getMeasurementValue("Cell: Max caliper") ?: 0.0) }
						if (cbMinCaliper.isSelected()) { vector << (cell.getMeasurementList().getMeasurementValue("Cell: Min caliper") ?: 0.0) }
						if (cbEccentricity.isSelected()) { vector << (cell.getMeasurementList().getMeasurementValue("Cell: Eccentricity") ?: 0.0) }
						return vector as double[]
					}
					def firstVec = getCombinedVector(insideCells[0])
					def sumVector = new double[firstVec.length]
					for (int i = 0; i < sumVector.length; i++) {
						sumVector[i] = 0.0
					}
					insideCells.each { cell ->
						def vec = getCombinedVector(cell)
						for (int i = 0; i < vec.length; i++) {
							sumVector[i] += vec[i]
						}
					}
					def avgVector = sumVector.collect { it / insideCells.size() } as double[]
					def outsideCells = allCells - insideCells
					def distances = outsideCells.collect { cell ->
						def vec = getCombinedVector(cell)
						double d = new EuclideanDistance().compute(avgVector, vec)
						[cell, d]
					}
					distances.sort { it[1] }
					finalCells = distances.take(topN).collect { it[0] }
					println "Combined filtering applied: using average vector from inside circle; selected ${finalCells.size()} cells outside."
				}
			} else {
				finalCells = spatialCells
				println "No specific filtering selected; using spatial filtering only."
			}

			def greenClass = PathClassFactory.getPathClass("Neighborhood-Green")
			finalCells.each { it.setPathClass(greenClass) }
			def selectionModel = hierarchy.getSelectionModel()
			selectionModel.clearSelection()
			selectionModel.setSelectedObjects([targetCell] + finalCells, targetCell)
			println "Neighborhood search complete. Found ${finalCells.size()} cells after filtering."

			dialog.setResult(null)
			dialog.close()
		}

		btnReset.setOnAction { event ->
			if (circleAnnotationRef != null) {
				hierarchy.removeObject(circleAnnotationRef, false)
				circleAnnotationRef = null
			}
			hierarchy.getSelectionModel().clearSelection()
			hierarchy.getDetectionObjects().findAll { it.isCell() }.each { it.setPathClass(null) }
			tfRadius.setText("50")
			tfTopN.setText("200")
			// Reset Marker Selections.
			[cbDAPI, cbNeuN, cbNFH, cbNFM, cbMAP2, cbSynaptophysin, cbCNPase, cbNBP].each { it.setSelected(false) }
			// Reset Morphological Features.
			[cbArea, cbPerimeter, cbCircularity, cbMaxCaliper, cbMinCaliper, cbEccentricity].each { it.setSelected(false) }
			// Reset Surround Markers.
			[cbSurroundDAPI, cbSurroundNeuN, cbSurroundNFH, cbSurroundNFM, cbSurroundMAP2, cbSurroundSynaptophysin, cbSurroundCNPase, cbSurroundNBP].each { it.setSelected(false) }
			println "Reset performed: cleared annotations, selection, and UI fields."
		}

		dialog.showAndWait()
	}

	// ----------------------------------------------------------------
	// COMPREHENSIVE SEARCH LOGIC
	// ----------------------------------------------------------------
	private void loadH5Model(QuPathGUI qupath) {
		// Placeholder for loading a .h5 model
		println "Model Selection triggered. (Placeholder for .h5 loading logic)"
		new Alert(AlertType.INFORMATION, "Model selection is not yet implemented.").showAndWait()
	}




	private static final Logger logger = Logger.getLogger(DemoGroovyExtension.class.getName())

	
	class SQLiteCellSearch {
		private static final String DB_PATH = "D:/similarity_matrix.db"

		static void runCellSearch(QuPathGUI qupath) {
			new Thread({
				Connection conn = null
				PreparedStatement stmt = null
				ResultSet rs = null

				try {
					// ✅ Load SQLite JDBC Driver
					Class.forName("org.sqlite.JDBC")
					conn = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH)

					if (conn == null) {
						Platform.runLater {
							new Alert(AlertType.ERROR, "❌ Failed to connect to SQLite.").showAndWait()
						}
						return
					}

					// ✅ Get Selected Cell from QuPath
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

					// ✅ Convert to Micrometer Units
					double pixelSize = imageData.getServer().getPixelCalibration().getPixelWidthMicrons()
					double centroidX = roi.getCentroidX() * pixelSize
					double centroidY = roi.getCentroidY() * pixelSize

					println "📍 Selected Cell Centroid: X = ${centroidX}, Y = ${centroidY}"

					// ✅ Find Closest Cell in `cell_coordinates` using Euclidean Distance
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

					println "✅ Matched Cell Index: ${cellIndex} (X = ${matchedX}, Y = ${matchedY})"

					// ✅ Get Top 5 Most Similar Cells from `top_similarities`
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

					// ✅ Always display centroid coordinates
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

					// ✅ Append Similar Cells Info to Alert
					alertMessage += "✅ Found ${similarCells.size()} similar cells:\n"
					similarCells.eachWithIndex { cellData, i ->
						alertMessage += "🔹 Similar Cell ${i + 1}: Index = ${cellData["cell_index"]}, X = ${cellData["x"]} µm, Y = ${cellData["y"]} µm\n"
					}

					// ✅ Highlight Similar Cells in QuPath
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

					// ✅ Update QuPath Selection
					def selectionModel = hierarchy.getSelectionModel()
					selectionModel.clearSelection()
					selectionModel.setSelectedObjects([targetCell] + matchingObjects, targetCell)

					// ✅ Show Results (Ensuring it runs on JavaFX Thread)
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
	// ----------------------------------------------------------------
	// NEW: CSV-BASED CLUSTER SEARCH
	// ----------------------------------------------------------------
	/**
	 * This function reads a clustering CSV file (with columns such as centroid_x, centroid_y, and level_0),
	 * finds the row whose centroid is closest to the selected cell, retrieves its level_0 cluster label,
	 * then finds all cells with that same cluster label and highlights them.
	 */
	private static void runCSVClusterSearch(QuPathGUI qupath) {
		// Path to your CSV file (update as needed)
		File csvFile = new File("D:/Clustering/leden_1.csv")
		if (!csvFile.exists()) {
			new Alert(AlertType.ERROR, "Clustering CSV file not found.").showAndWait()
			return
		}

		// Read CSV into a list of maps (assuming tab-delimited file)
		def rows = []
		def header = []
		csvFile.eachLine { line, count ->
			def parts = line.split("\\t")
			if (count == 1) {
				header = parts
			} else {
				def row = [:]
				for (int i = 0; i < header.size(); i++) {
					row[header[i]] = parts[i]
				}
				rows.add(row)
			}
		}

		// Get selected cell from QuPath
		def imageData = qupath.getImageData()
		if (imageData == null) {
			new Alert(AlertType.WARNING, "No image data available.").showAndWait()
			return
		}
		def hierarchy = imageData.getHierarchy()
		def selectedCells = hierarchy.getSelectionModel().getSelectedObjects().findAll { it.isCell() }
		if (selectedCells.isEmpty()) {
			new Alert(AlertType.WARNING, "Please select a cell before running CSV cluster search!").showAndWait()
			return
		}
		def targetCell = selectedCells[0]
		def targetROI = targetCell.getROI()
		double targetX = targetROI.getCentroidX()
		double targetY = targetROI.getCentroidY()


		// Find the row in the CSV with the closest centroid
		def closestRow = null
		def minDist = Double.MAX_VALUE
		rows.each { row ->
			double cx = row['centroid_x'] as double
			double cy = row['centroid_y'] as double
			double dist = Math.sqrt((cx - targetX) * (cx - targetX) + (cy - targetY) * (cy - targetY))
			if (dist < minDist) {
				minDist = dist
				closestRow = row
			}
		}

		if (closestRow == null) {
			new Alert(AlertType.WARNING, "No matching cell found in clustering CSV!").showAndWait()
			return
		}

		// Get the cluster label (level_0) for the selected cell
		def clusterLabel = closestRow['level_0']
		println "Selected cell cluster level_0: " + clusterLabel

		// Filter rows with the same level_0 value
		def matchingRows = rows.findAll { row -> row['level_0'] == clusterLabel }
		println "Found " + matchingRows.size() + " rows with level_0 = " + clusterLabel

		// Find and highlight the corresponding cells in the image.
		def matchedCells = []
		double tolerance = 5.0  // tolerance in pixels for matching centroids
		def allCells = hierarchy.getDetectionObjects().findAll { it.isCell() }
		allCells.each { cell ->
			def c = cell.getROI().getCentroid()
			matchingRows.each { row ->
				double cx = row['centroid_x'] as double
				double cy = row['centroid_y'] as double
				double dx = c.getX() - cx
				double dy = c.getY() - cy
				if ((dx * dx + dy * dy) <= tolerance * tolerance) {
					matchedCells.add(cell)
					return // exit inner loop if matched
				}
			}
		}

		if (matchedCells.isEmpty()) {
			new Alert(AlertType.WARNING, "No matching cells found in the image for cluster " + clusterLabel).showAndWait()
			return
		}

		// Highlight the matched cells with a specific PathClass (unique per cluster)
		def clusterClass = PathClassFactory.getPathClass("CSV-Cluster-" + clusterLabel)
		matchedCells.each { it.setPathClass(clusterClass) }

		// Update the QuPath selection
		def selectionModel = hierarchy.getSelectionModel()
		selectionModel.clearSelection()
		selectionModel.setSelectedObjects(matchedCells, targetCell)
		println "CSV Cluster search complete. Highlighted " + matchedCells.size() + " cells with level_0 = " + clusterLabel
	}

// ----------------------------------------------------------------
	// FEATURE EXTRACTION METHODS (Updated for morphology)
	// ----------------------------------------------------------------
	/**
	 * Extract morphological features for a cell:
	 *  - Area
	 *  - Perimeter
	 *  - Circularity
	 *  - Max caliper
	 *  - Min caliper
	 *  - Eccentricity
	 */
	private static double[] extractMorphologicalFeatures(PathObject cell) {
		def measurementList = cell.getMeasurementList()
		double area         = measurementList.getMeasurementValue("Cell: Area")         ?: 0.0
		double perimeter    = measurementList.getMeasurementValue("Cell: Perimeter")    ?: 0.0
		double circularity  = measurementList.getMeasurementValue("Cell: Circularity")  ?: 0.0
		double maxCaliper   = measurementList.getMeasurementValue("Cell: Max caliper")  ?: 0.0
		double minCaliper   = measurementList.getMeasurementValue("Cell: Min caliper")  ?: 0.0
		double eccentricity = measurementList.getMeasurementValue("Cell: Eccentricity") ?: 0.0

		// Return as a vector
		return [
				area,
				perimeter,
				circularity,
				maxCaliper,
				minCaliper,
				eccentricity
		] as double[]
	}

	/**
	 * Extract marker-based features (fluorescence intensities, IHC stains, etc.).
	 * Update channel/stain names as appropriate for your dataset.
	 */
	private static double[] extractMarkerFeatures(PathObject cell) {
		def measurementList = cell.getMeasurementList()
		// Example channels
		double dapiMean  = measurementList.getMeasurementValue("Cell: DAPI mean")  ?: 0.0
		double neuNMean  = measurementList.getMeasurementValue("Cell: NeuN mean")  ?: 0.0
		return [dapiMean, neuNMean] as double[]
	}

	/**
	 * Combine morphological + marker features for a more comprehensive feature vector.
	 */
	private static double[] extractCombinedFeatures(PathObject cell) {
		double[] morph  = extractMorphologicalFeatures(cell)
		double[] marker = extractMarkerFeatures(cell)

		// Create a new double[] of the combined length
		double[] combined = new double[morph.length + marker.length]

		// Copy the first array into combined
		System.arraycopy(morph, 0, combined, 0, morph.length)
		// Copy the second array into combined
		System.arraycopy(marker, 0, combined, morph.length, marker.length)

		return combined
	}

}
