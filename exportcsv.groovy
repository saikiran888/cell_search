
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

		// PART 2: Morphological Features – 6 checkboxes in 2 columns.
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

		// Bottom row: Radius (micrometers), Top N, GO, Reset, and Export CSV buttons.
		Label radiusLabel = new Label("Radius (micrometers):")
		radiusLabel.setStyle("-fx-font-weight: bold;")
		TextField tfRadius = new TextField("50")  // default radius
		Button btnGo = new Button("GO")
		Button btnReset = new Button("Reset")
		Button btnExport = new Button("Export CSV")

		// Export CSV event handler:
		btnExport.setOnAction { event ->
			// Gather highlighted cells (the current selection)
			def highlightedCells = hierarchy.getSelectionModel().getSelectedObjects().findAll { it.isCell() }
			if (highlightedCells.isEmpty()) {
				new Alert(Alert.AlertType.WARNING, "No highlighted cells to export.").showAndWait()
				return
			}
			FileChooser fileChooser = new FileChooser()
			fileChooser.setTitle("Save CSV")
			fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV Files", "*.csv"))
			// Use the viewer's view node to get the window:
			def window = qupath.getViewer().getView().getScene().getWindow()
			File file = fileChooser.showSaveDialog(window)
			if (file == null) {
				return
			}
			file.withPrintWriter { pw ->
				pw.println("CentroidX,CentroidY")
				highlightedCells.each { cell ->
					def roi = cell.getROI()
					pw.println("${roi.getCentroidX()},${roi.getCentroidY()}")
				}
			}
			new Alert(Alert.AlertType.INFORMATION, "CSV exported successfully!").showAndWait()
		}

		HBox bottomRow = new HBox(10, radiusLabel, tfRadius, topNLabel, tfTopN, btnGo, btnReset, btnExport)
		bottomRow.setAlignment(Pos.CENTER_RIGHT)

		// Put everything together in one main container.
		VBox dialogContent = new VBox(10, markerBox, morphBox, surroundBox, bottomRow)
		dialogContent.setStyle("-fx-padding: 20px; -fx-spacing: 15px;")

		Dialog<Void> dialog = new Dialog<>()
		dialog.setTitle("Neighborhood Search Options")
		dialog.setHeaderText("Configure neighborhood search parameters")
		dialog.getDialogPane().setContent(dialogContent)
		dialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL)

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

			// Note: Removed dialog.close() so that the dialog stays open after running GO.
			// dialog.setResult(null)
			// dialog.close()
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
			[cbDAPI, cbNeuN, cbNFH, cbNFM, cbMAP2, cbSynaptophysin, cbCNPase, cbNBP].each { it.setSelected(false) }
			[cbArea, cbPerimeter, cbCircularity, cbMaxCaliper, cbMinCaliper, cbEccentricity].each { it.setSelected(false) }
			[cbSurroundDAPI, cbSurroundNeuN, cbSurroundNFH, cbSurroundNFM, cbSurroundMAP2, cbSurroundSynaptophysin, cbSurroundCNPase, cbSurroundNBP].each { it.setSelected(false) }
			println "Reset performed: cleared annotations, selection, and UI fields."
		}

		dialog.showAndWait()
	}

