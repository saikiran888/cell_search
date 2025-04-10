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
