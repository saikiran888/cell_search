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

		// (B) MySQL-based advanced search
		def mysqlSearchItem = new MenuItem("MySQL-based Search")
		mysqlSearchItem.setOnAction(e -> {
			runMySQLSearch(qupath)
		})

		// Add these items to the Comprehensive Search menu
		comprehensiveMenu.getItems().addAll(modelSelectionItem, mysqlSearchItem)

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
			new Alert(AlertType.WARNING, "No image data available.").showAndWait()
			return
		}

		def hierarchy = imageData.getHierarchy()
		def selectedCells = hierarchy.getSelectionModel().getSelectedObjects().findAll { it.isCell() }
		if (selectedCells.isEmpty()) {
			new Alert(AlertType.WARNING, "Please select a single cell before running neighborhood search!").showAndWait()
			return
		}

		def targetCell = selectedCells[0]
		println "Neighborhood search for cell: ID = ${targetCell.getID()}"

		// Example: define a search radius in pixels (or microns if you convert)
		double radius = 50.0

		// Filter for cells within radius
		def allCells = hierarchy.getDetectionObjects().findAll { it.isCell() }
		def center = targetCell.getROI().getCentroid()
		def neighborCells = allCells.findAll { cell ->
			def c = cell.getROI().getCentroid()
			double dx = c.getX() - center.getX()
			double dy = c.getY() - center.getY()
			return (dx*dx + dy*dy) <= radius*radius
		}

		// Highlight them in a separate color
		def greenClass = PathClassFactory.getPathClass("Neighborhood-Green")
		neighborCells.each { it.setPathClass(greenClass) }

		// Update QuPath selection
		def selectionModel = hierarchy.getSelectionModel()
		selectionModel.clearSelection()
		selectionModel.setSelectedObjects([targetCell] + neighborCells, targetCell)

		println "Neighborhood search complete. Found ${neighborCells.size()} cells within ${radius} pixels."
	}

	// ----------------------------------------------------------------
	// COMPREHENSIVE SEARCH LOGIC
	// ----------------------------------------------------------------
	private void loadH5Model(QuPathGUI qupath) {
		// Placeholder for loading a .h5 model
		println "Model Selection triggered. (Placeholder for .h5 loading logic)"
		new Alert(AlertType.INFORMATION, "Model selection is not yet implemented.").showAndWait()
	}

	private static void runMySQLSearch(QuPathGUI qupath) {
		new Thread({
			Connection conn = null
			Statement stmt = null
			ResultSet rs = null
			try {
				// ✅ Explicitly Register the MySQL Driver Before Connecting
				Driver driver = new com.mysql.cj.jdbc.Driver()
				DriverManager.registerDriver(driver)
				println "✅ MySQL Driver Registered Successfully!"

				// ✅ Define the correct database URL
				def url = "jdbc:mysql://localhost:3306/similarity_matrix?useSSL=false"
				def username = "root"
				def password = "root"

				// ✅ Establish the database connection
				conn = DriverManager.getConnection(url, username, password)
				if (conn == null) {
					throw new SQLException("❌ Failed to establish a connection to MySQL.")
				}

				println "✅ Successfully connected to MySQL!"

				// ✅ Example Query
				stmt = conn.createStatement()
				rs = stmt.executeQuery("SELECT COUNT(*) AS count FROM similarity_matrix.cell_coordinates")

				if (rs.next()) {
					int count = rs.getInt("count")
					println "✅ Database contains ${count} cells."
				}

			} catch (SQLException ex) {
				Platform.runLater {
					new Alert(AlertType.ERROR, "❌ SQL Error: " + ex.message).showAndWait()
				}
				ex.printStackTrace()

			} finally {
				try {
					if (rs != null) rs.close()
					if (stmt != null) stmt.close()
					if (conn != null) conn.close()
					println "✅ MySQL Connection Closed."
				} catch (SQLException e) {
					println "⚠️ Failed to close MySQL resources: " + e.message
				}
			}
		}).start()
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
