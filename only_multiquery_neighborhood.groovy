// Enhanced Neighborhood Search with Global Similarity + Multi-Cell Set Operations (Optimized with Caching)
	private static void runMultiQuerySearch(QuPathGUI qupath) {
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
		Label markerLabel = new Label("Center-cell Markers:")
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
		def morphCbs = [cbArea, cbPerimeter, cbCircularity, cbMaxCaliper, cbMinCaliper, cbEccentricity]
		VBox morphCol1 = new VBox(5, cbArea, cbPerimeter)
		VBox morphCol2 = new VBox(5, cbCircularity, cbMaxCaliper)
		VBox morphCol3 = new VBox(5, cbMinCaliper, cbEccentricity)
		HBox morphHBox = new HBox(10, morphCol1, morphCol2, morphCol3)
		CheckBox cbMorphSelectAll = new CheckBox("Select All")
		cbMorphSelectAll.setOnAction {
			boolean value = cbMorphSelectAll.isSelected()
			morphCbs.each { it.setSelected(value) }
		}
		Label morphLabel = new Label("Center-cell Morphology:")
		morphLabel.setStyle("-fx-font-weight: bold;")
		VBox morphBox = new VBox(5, morphLabel, cbMorphSelectAll, morphHBox)

		// Surround markers
		def surroundCheckboxes = markerLabels.collect { new CheckBox(it) }
		CheckBox cbSurroundSelectAll = new CheckBox("Select All")
		cbSurroundSelectAll.setOnAction {
			boolean value = cbSurroundSelectAll.isSelected()
			surroundCheckboxes.each { it.setSelected(value) }
		}
		Label surroundLabel = new Label("Neighborhood Markers:")
		surroundLabel.setStyle("-fx-font-weight: bold;")
		def surroundHBox = partitionCheckboxes(surroundCheckboxes, 4)
		VBox surroundBox = new VBox(5, surroundLabel, cbSurroundSelectAll, surroundHBox)

		// Set operation checkboxes
		def cbUnion = new CheckBox("Union")
		def cbIntersection = new CheckBox("Intersection")
		def cbSubtract = new CheckBox("Set Difference")
		def cbContrastive = new CheckBox("Contrastive")
		def enforceSingleOp = { changed -> [cbUnion, cbIntersection, cbSubtract, cbContrastive].each { if (it != changed) it.setSelected(false) } }
		[cbUnion, cbIntersection, cbSubtract, cbContrastive].each { cb ->
			cb.selectedProperty().addListener({ obs, old, newVal -> if (newVal) enforceSingleOp(cb) } as ChangeListener)
		}
		VBox setOpsBox = new VBox(5, new Label("Set Operation:"), cbUnion, cbIntersection, cbSubtract, cbContrastive)

		// Radius / TopN fields
		Label topNLabel = new Label("Top N:")
		topNLabel.setStyle("-fx-font-weight: bold;")
		TextField tfTopN = new TextField("4000")
		Label radiusLabel = new Label("Radius (micrometers):")
		radiusLabel.setStyle("-fx-font-weight: bold;")
		TextField tfRadius = new TextField("50")
		Button btnGo = new Button("Run")
		Button btnReset = new Button("Reset")
		Button btnExport = new Button("Export")

		btnGo.setOnAction {
			def selectedCells = hierarchy.getSelectionModel().getSelectedObjects().findAll { it.isCell() }
			if (selectedCells.size() < 2) {
				new Alert(Alert.AlertType.WARNING, "Please select at least 2 cells for neighborhood set operations.").show()
				return
			}

			def allCells = hierarchy.getDetectionObjects().findAll { it.isCell() }
			double radiusMicrons = tfRadius.getText().toDouble()
			int topN = tfTopN.getText().toInteger()
			double pixelSize = imageData.getServer().getPixelCalibration().getPixelWidthMicrons()
			double radiusPixels = radiusMicrons / pixelSize

			def computeNeighborhoodVector = { center ->
				def cx = center.getROI().getCentroidX()
				def cy = center.getROI().getCentroidY()
				def neighbors = allCells.findAll { c ->
					def dx = c.getROI().getCentroidX() - cx
					def dy = c.getROI().getCentroidY() - cy
					dx * dx + dy * dy <= radiusPixels * radiusPixels && c != center
				}
				def vec = []
				markerCheckboxes.findAll { it.isSelected() }.each { cb ->
					def values = neighbors.collect { it.getMeasurementList().getMeasurementValue("Cell: ${cb.getText()} mean") ?: 0.0 }
					vec << (values ? values.sum() / values.size() : 0.0)
				}
				morphCbs.each { cb ->
					if (cb.isSelected()) {
						vec << (center.getMeasurementList().getMeasurementValue("Cell: ${cb.getText()}") ?: 0.0)
					}
				}
				return vec as double[]
			}

			// 🚀 Precompute all vectors once
			def cachedVectors = allCells.collectEntries { c -> [c, computeNeighborhoodVector(c)] }

			def sets = selectedCells.collect { center ->
				def targetVec = cachedVectors[center]
				def dists = allCells.findAll { it != center }.collect { c ->
					[c, new EuclideanDistance().compute(targetVec, cachedVectors[c])]
				}
				dists.sort { it[1] }
				dists.take(topN).collect { it[0] } as Set
			}

			def result
			if (cbUnion.isSelected()) result = sets.flatten().toSet()
			else if (cbIntersection.isSelected()) result = sets.inject(sets[0]) { acc, s -> acc.intersect(s) }
			else if (cbSubtract.isSelected()) result = sets[0] - sets[1..-1].flatten()
			else if (cbContrastive.isSelected()) {
				if (selectedCells.size() != 2) {
					new Alert(Alert.AlertType.WARNING, "Contrastive requires exactly 2 cells.").show()
					return
				}
				result = sets[0] - sets[1]
			} else {
				new Alert(Alert.AlertType.WARNING, "Please select a set operation.").show()
				return
			}

			def searchClass = PathClassFactory.getPathClass("Neighborhood-Search")
			result.each { it.setPathClass(searchClass) }
			hierarchy.getSelectionModel().setSelectedObjects(result.toList(), null)
			println "Neighborhood multi-cell search complete. Found ${result.size()} matches."
		}

		btnReset.setOnAction {
			hierarchy.getDetectionObjects().findAll { it.isCell() }.each { it.setPathClass(null) }
			hierarchy.getSelectionModel().clearSelection()
		}

		btnExport.setOnAction {
			def selected = hierarchy.getSelectionModel().getSelectedObjects().findAll { it.isCell() }
			if (selected.isEmpty()) {
				new Alert(Alert.AlertType.WARNING, "No selected cells to export.").show()
				return
			}
			FileChooser fileChooser = new FileChooser()
			fileChooser.setTitle("Export CSV")
			fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV Files", "*.csv"))
			def file = fileChooser.showSaveDialog(qupath.getStage())
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

		HBox bottomRow = new HBox(10, radiusLabel, tfRadius, topNLabel, tfTopN, btnGo, btnReset, btnExport)
		bottomRow.setAlignment(Pos.CENTER_RIGHT)
		VBox dialogContent = new VBox(10, markerBox, morphBox, surroundBox, setOpsBox, bottomRow)
		dialogContent.setStyle("-fx-padding: 20px; -fx-spacing: 15px;")

		Stage stage = new Stage()
		stage.setTitle("Neighborhood Search (Multi-cell)")
		stage.initOwner(qupath.getStage())
		stage.setScene(new Scene(dialogContent))
		stage.show()
	}
