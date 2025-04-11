	private static void runMultiQuerySearch(QuPathGUI qupath) {
		def imageData = qupath.getImageData()
		if (imageData == null) {
			def alert = new Alert(AlertType.WARNING, "No image data available.")
			alert.initOwner(qupath.getStage())
			alert.show()
			return
		}
		def hierarchy = imageData.getHierarchy()

		// --- Build dynamic marker checkboxes (Center markers) ---
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

		// --- Neighborhood marker checkboxes ---
		def neighborhoodMarkerCheckboxes = markerLabels.collect { new CheckBox(it) }
		CheckBox cbNeighborhoodSelectAll = new CheckBox("Select All Neighborhood Markers")
		cbNeighborhoodSelectAll.setOnAction {
			boolean value = cbNeighborhoodSelectAll.isSelected()
			neighborhoodMarkerCheckboxes.each { it.setSelected(value) }
		}
		VBox neighborhoodMarkerBox = new VBox(5, new Label("Neighborhood Marker Selections:"), cbNeighborhoodSelectAll, partitionCheckboxes(neighborhoodMarkerCheckboxes, 4))

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

		// --- Neighborhood Radius field ---
		Label neighborhoodRadiusLabel = new Label("Neighborhood Radius (microns):")
		TextField tfNeighborhoodRadius = new TextField("50")
		HBox neighborhoodRadiusBox = new HBox(10, neighborhoodRadiusLabel, tfNeighborhoodRadius)
		neighborhoodRadiusBox.setAlignment(Pos.CENTER_LEFT)

		// --- Buttons ---
		def btnRun = new Button("Run")
		def btnExport = new Button("Export CSV")
		def btnReset = new Button("Reset")
		def btnClose = new Button("Close")
		final Stage dialogStage = new Stage()

		// --- Define extraction method using dynamic selections ---
		// Now includes center markers, morphology, and neighborhood marker averages.
		def extractionMethod = { cell, allCells, double radiusPixels ->
			def vec = []
			// Center markers
			if (markerCheckboxes.any { it.isSelected() }) {
				markerCheckboxes.findAll { it.isSelected() }.each { cb ->
					def value = cell.getMeasurementList().getMeasurementValue("Cell: " + cb.getText() + " mean") ?: 0.0
					vec << value
				}
			}
			// Morphological features
			def morphCbs = [cbArea, cbPerimeter, cbCircularity, cbMaxCaliper, cbMinCaliper, cbEccentricity]
			if (morphCbs.any { it.isSelected() }) {
				if (cbArea.isSelected()) { vec << (cell.getMeasurementList().getMeasurementValue("Cell: Area") ?: 0.0) }
				if (cbPerimeter.isSelected()) { vec << (cell.getMeasurementList().getMeasurementValue("Cell: Perimeter") ?: 0.0) }
				if (cbCircularity.isSelected()) { vec << (cell.getMeasurementList().getMeasurementValue("Cell: Circularity") ?: 0.0) }
				if (cbMaxCaliper.isSelected()) { vec << (cell.getMeasurementList().getMeasurementValue("Cell: Max caliper") ?: 0.0) }
				if (cbMinCaliper.isSelected()) { vec << (cell.getMeasurementList().getMeasurementValue("Cell: Min caliper") ?: 0.0) }
				if (cbEccentricity.isSelected()) { vec << (cell.getMeasurementList().getMeasurementValue("Cell: Eccentricity") ?: 0.0) }
			}
			// Neighborhood markers: compute average for each selected marker over cells in the neighborhood
			if (neighborhoodMarkerCheckboxes.any { it.isSelected() }) {
				def roi = cell.getROI()
				double centerX = roi.getCentroidX()
				double centerY = roi.getCentroidY()
				def neighbors = allCells.findAll { it != cell }.findAll {
					def r = it.getROI()
					double dx = r.getCentroidX() - centerX
					double dy = r.getCentroidY() - centerY
					return (dx * dx + dy * dy) <= (radiusPixels * radiusPixels)
				}
				neighborhoodMarkerCheckboxes.findAll { it.isSelected() }.each { cb ->
					def markerName = "Cell: " + cb.getText() + " mean"
					def values = neighbors.collect { it.getMeasurementList().getMeasurementValue(markerName) ?: 0.0 }
					double avg = values ? (values.sum() / values.size()) : 0.0
					vec << avg
				}
			}
			if (vec.isEmpty()) {
				return [0.0] as double[]
			} else {
				return vec as double[]
			}
		}

		// --- Run button action with progress bar and background Task ---
		btnRun.setOnAction {
			def selectedCells = hierarchy.getSelectionModel().getSelectedObjects().findAll { it.isCell() }
			if (selectedCells.size() > 20) {
				def alert = new Alert(AlertType.WARNING, "Too many cells selected (more than 20). Please select fewer cells to avoid memory issues.")
				alert.initOwner(qupath.getStage())
				alert.show()
				return
			}
			if (selectedCells.size() < 2) {
				def alert = new Alert(AlertType.WARNING, "Please select at least 2 cells for Multi-Query Search.")
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
			if (!(markerCheckboxes.any { it.isSelected() } ||
					[cbArea, cbPerimeter, cbCircularity, cbMaxCaliper, cbMinCaliper, cbEccentricity].any { it.isSelected() } ||
					neighborhoodMarkerCheckboxes.any { it.isSelected() })) {
				def alert = new Alert(AlertType.WARNING, "Please select at least one marker, morphological feature, or neighborhood marker.")
				alert.initOwner(qupath.getStage())
				alert.show()
				return
			}
			def allCells = hierarchy.getDetectionObjects().findAll { it.isCell() }
			double pixelSize = imageData.getServer().getPixelCalibration().getPixelWidthMicrons()
			double neighborhoodRadiusMicrons
			try {
				neighborhoodRadiusMicrons = Double.parseDouble(tfNeighborhoodRadius.getText())
			} catch (Exception ex) {
				def alert = new Alert(AlertType.WARNING, "Invalid Neighborhood Radius value.")
				alert.initOwner(qupath.getStage())
				alert.show()
				return
			}
			double radiusPixels = neighborhoodRadiusMicrons / pixelSize

			// Setup a progress bar and a progress dialog
			ProgressBar progressBar = new ProgressBar(0)
			progressBar.setPrefWidth(300)
			Label progressLabel = new Label("Running multi-query search...")
			VBox progressBox = new VBox(10, progressLabel, progressBar)
			progressBox.setPadding(new Insets(20))
			Stage progressStage = new Stage()
			progressStage.setTitle("Multi-Query Search Progress")
			progressStage.initOwner(qupath.getStage())
			progressStage.setScene(new Scene(progressBox))
			progressStage.show()

			// Wrap the multi-query search in a Task to update progress
			Task<Void> task = new Task<Void>() {
				@Override
				protected Void call() throws Exception {
					def similarSets = []
					int totalQueries = selectedCells.size()
					int counter = 0
					selectedCells.each { cell ->
						def targetVec = extractionMethod(cell, allCells, radiusPixels)
						def distances = allCells.findAll { it != cell }.collect { other ->
							def otherVec = extractionMethod(other, allCells, radiusPixels)
							[other, new EuclideanDistance().compute(targetVec, otherVec)]
						}
						distances.sort { it[1] }
						// For each query cell, take the top N similar cells as a Set
						def similar = distances.take(limit).collect { it[0] } as Set
						similarSets << similar
						counter++
						updateProgress(counter, totalQueries)
						updateMessage("Processed $counter of $totalQueries query cells...")
					}
					def finalResults = []
					if (!(cbUnion.isSelected() || cbIntersection.isSelected() || cbSubtract.isSelected() || cbContrastive.isSelected())) {
						throw new Exception("Please select a multi-query operation.")
					}
					if (cbContrastive.isSelected() && selectedCells.size() != 2) {
						throw new Exception("Contrastive Search requires exactly 2 selected cells.")
					}
					if (cbUnion.isSelected()) {
						def unionList = []
						similarSets.each { unionList.addAll(it) }
						finalResults = unionList
					} else if (cbIntersection.isSelected()) {
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
					Platform.runLater {
						def multiQueryClass = PathClass.fromString("Multi-Query-Green")
						finalResults.each { it.setPathClass(multiQueryClass) }
						hierarchy.getSelectionModel().clearSelection()
						hierarchy.getSelectionModel().setSelectedObjects(finalResults as Collection<? extends PathObject>, null)
						progressStage.close()
						println "Multi-Query Search complete. Selected ${finalResults.size()} cells."
					}
					return null
				}
			}
			progressBar.progressProperty().bind(task.progressProperty())
			progressLabel.textProperty().bind(task.messageProperty())

			Thread thread = new Thread(task)
			thread.setDaemon(true)
			thread.start()
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
				def alert = new Alert(AlertType.INFORMATION, "Exported ${selected.size()} cells.")
				alert.initOwner(qupath.getStage())
				alert.show()
			}
		}

		btnReset.setOnAction {
			hierarchy.getDetectionObjects().findAll { it.isCell() }.each { it.setPathClass(null) }
			hierarchy.getSelectionModel().clearSelection()
		}

		btnClose.setOnAction { dialogStage.close() }

		// Arrange all UI components into titled panes
		TitledPane tpBasis = new TitledPane("Marker Selections", markerBox)
		tpBasis.setCollapsible(false)
		TitledPane tpNeigh = new TitledPane("Neighborhood Marker Selections", neighborhoodMarkerBox)
		tpNeigh.setCollapsible(false)
		TitledPane tpMorph = new TitledPane("Morphological Features", morphBox)
		tpMorph.setCollapsible(false)
		TitledPane tpOps = new TitledPane("Multi-Query Operation", opBox)
		tpOps.setCollapsible(false)
		VBox layout = new VBox(10, tpBasis, tpNeigh, tpMorph, tpOps, topNBox, neighborhoodRadiusBox, new HBox(10, btnRun, btnExport, btnReset, btnClose))
		layout.setPadding(new Insets(20))

		dialogStage.setTitle("Multi-Query Search")
		dialogStage.initOwner(qupath.getStage())
		dialogStage.setScene(new Scene(layout))
		dialogStage.show()
	}
