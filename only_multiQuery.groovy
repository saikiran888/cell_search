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
