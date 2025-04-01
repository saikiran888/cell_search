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
