// Function to run CSV cluster search with a drop-down level selection dialog.
	private static void runCSVClusterSearch(QuPathGUI qupath) {
		// === Step 1. Show a dialog to select the cluster level column ===
		def levelOptions = ["level_1", "level_2", "level_3", "level_4", "level_5", "level_6"]
		Dialog<String> dialog = new Dialog<>()
		dialog.setTitle("CSV Cluster Search")
		dialog.setHeaderText("Select the cluster column to use for search:")

		ComboBox<String> comboBox = new ComboBox<>()
		comboBox.getItems().addAll(levelOptions)
		comboBox.setValue("level_1")  // default selection

		VBox dialogVBox = new VBox(10, new javafx.scene.control.Label("Cluster Level:"), comboBox)
		dialogVBox.setPadding(new Insets(20))
		dialog.getDialogPane().setContent(dialogVBox)

		ButtonType runButtonType = new ButtonType("Run", ButtonData.OK_DONE)
		dialog.getDialogPane().getButtonTypes().addAll(runButtonType, ButtonType.CANCEL)

		dialog.setResultConverter { buttonType ->
			if (buttonType == runButtonType) {
				return comboBox.getValue()
			}
			return null
		}

		def result = dialog.showAndWait()
		if (!result.isPresent()) {
			println "User canceled CSV cluster search."
			return
		}
		String chosenLevel = result.get()
		println "User selected cluster column: " + chosenLevel

		// === Step 2. Read the CSV file ===
		// Update the CSV file path as needed
		File csvFile = new File("C:/Users/saiki/OneDrive/Desktop/sai.csv")
		if (!csvFile.exists()) {
			new Alert(AlertType.ERROR, "Clustering CSV file not found.").showAndWait()
			return
		}

		def rows = []
		def header = []
		int lineCount = 0
		csvFile.eachLine { line ->
			lineCount++
			def parts = line.split(",")   // using comma as delimiter
			if (lineCount == 1) {
				header = parts
			} else {
				// If row is missing fields, fill missing ones with empty strings.
				if (parts.size() < header.size()) {
					def newParts = new String[header.size()]
					for (int i = 0; i < header.size(); i++) {
						newParts[i] = (i < parts.size()) ? parts[i] : ""
					}
					parts = newParts
				}
				def row = [:]
				for (int i = 0; i < header.size(); i++) {
					row[header[i]] = parts[i]
				}
				rows.add(row)
			}
		}
		if (rows.size() > 0) {
			println "First CSV row: " + rows[0]
		}

		// === Step 3. Get the selected cell from QuPath ===
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
		println "Target cell coordinates: (" + targetX + ", " + targetY + ")"

		// === Step 4. Find the CSV row with the closest centroid (using keys "x" and "y") ===
		def closestRow = null
		def minDist = Double.MAX_VALUE
		rows.each { row ->
			if (row['x'] && row['y']) {
				double cx = row['x'] as double
				double cy = row['y'] as double
				double dist = Math.sqrt((cx - targetX) * (cx - targetX) + (cy - targetY) * (cy - targetY))
				if (dist < minDist) {
					minDist = dist
					closestRow = row
				}
			}
		}
		if (closestRow == null) {
			new Alert(AlertType.WARNING, "No matching cell found in clustering CSV!").showAndWait()
			return
		}

		// === Step 5. Retrieve the cluster label from the chosen level column ===
		def clusterLabel = closestRow[chosenLevel]
		println "Selected cell cluster " + chosenLevel + ": " + clusterLabel

		// Filter rows with the same chosen level value
		def matchingRows = rows.findAll { row -> row[chosenLevel] == clusterLabel }
		println "Found " + matchingRows.size() + " rows with " + chosenLevel + " = " + clusterLabel

		// === Step 6. Find and highlight the corresponding cells in the image ===
		def matchedCells = []
		double tolerance = 10.0  // tolerance in pixels (adjust if needed)
		def allCells = hierarchy.getDetectionObjects().findAll { it.isCell() }
		allCells.each { cell ->
			// Using getCentroidX() and getCentroidY() if getCentroid() is not available:
			double cellX = cell.getROI().getCentroidX()
			double cellY = cell.getROI().getCentroidY()
			matchingRows.each { row ->
				if (row['x'] && row['y']) {
					double cx = row['x'] as double
					double cy = row['y'] as double
					double dx = cellX - cx
					double dy = cellY - cy
					if ((dx * dx + dy * dy) <= (tolerance * tolerance)) {
						matchedCells.add(cell)
						return // exit inner loop once matched
					}
				}
			}
		}
		if (matchedCells.isEmpty()) {
			new Alert(AlertType.WARNING, "No matching cells found in the image for cluster " + clusterLabel).showAndWait()
			return
		}

		// === Step 7. Highlight the matched cells with a specific PathClass (unique per cluster) ===
		def clusterClass = getCurrentViewer().getPathClass("CSV-Cluster-" + chosenLevel + "-" + clusterLabel)
		if (clusterClass == null) {
			clusterClass = new PathClass("CSV-Cluster-" + chosenLevel + "-" + clusterLabel, javafx.scene.paint.Color.LIME, 1.0)
		}
		matchedCells.each { it.setPathClass(clusterClass) }

		// Update the QuPath selection
		def selectionModel = hierarchy.getSelectionModel()
		selectionModel.clearSelection()
		selectionModel.setSelectedObjects(matchedCells, targetCell)
		println "CSV Cluster search complete. Highlighted " + matchedCells.size() + " cells with " + chosenLevel + " = " + clusterLabel
	}

