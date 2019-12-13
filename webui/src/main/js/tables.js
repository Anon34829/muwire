class Link {
	constructor(text, call, params) {
		this.text = text
		this.call = call
		this.params = params
	}
	
	render() {
		return "<a href='#' onclick='window." + this.call +"(\"" + this.params.join("\",\"") + "\");return false;'>" + this.text + "</a>"
	}
}

class Column {
	constructor(key) {
		this.key = key
	}
	
	render(descending, callback) {
		var html = "<th>"
		var linkText = _t(this.key)
		var link = new Link(linkText, callback, [this.key, descending])
		html += link.render() + "</th>"
		return html
	}
}

class Table {
	constructor(columns, callback, key, descending) {
		this.columns = columns.map(x => new Column(x))
		this.callback = callback
		this.rows = []
		this.key = key
		this.descending = descending
	}
	
	addRow(mapping) {
		this.rows.push(mapping)
	}
	
	render() {
		var html = "<table><thead><tr>"
		var i
		for (i = 0;i < this.columns.length; i++) {
			if (this.columns[i].key == this.key)
				html += this.columns[i].render(this.descending, this.callback)
			else
				html += this.columns[i].render("descending", this.callback)
		}
		html += "</tr></thead><tbody>"
		
		for (i = 0; i < this.rows.length; i++) {
			html += "<tr>"
			var j
			for (j = 0; j < this.columns.length; j++) {
				var key = this.columns[j].key
				var value = this.rows[i].get(key)
				html += "<td>" + value + "</td>"
			}
			html += "</tr>"
		}
		
		html += "</tbody></table>"
		return html
	}
}