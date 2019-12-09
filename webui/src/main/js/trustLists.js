class TrustList {
	constructor(xmlNode) {
		this.user = xmlNode.getElementsByTagName("User")[0].childNodes[0].nodeValue
		this.userB64 = xmlNode.getElementsByTagName("UserB64")[0].childNodes[0].nodeValue
		this.status = xmlNode.getElementsByTagName("Status")[0].childNodes[0].nodeValue
		this.timestamp = xmlNode.getElementsByTagName("Timestamp")[0].childNodes[0].nodeValue
		this.trusted = xmlNode.getElementsByTagName("Trusted")[0].childNodes[0].nodeValue
		this.distrusted = xmlNode.getElementsByTagName("Distrusted")[0].childNodes[0].nodeValue
	}
}

class Persona {
	constructor(xmlNode) {
		this.user = xmlNode.getElementsByTagName("User")[0].childNodes[0].nodeValue
		this.userB64 = xmlNode.getElementsByTagName("UserB64")[0].childNodes[0].nodeValue
		try {
			this.reason = xmlNode.getElementsByTagName("Reason")[0].childNodes[0].nodeValue
		} catch (ignore) {
			this.reason = ""
		}
		this.status = xmlNode.getElementsByTagName("Status")[0].childNodes[0].nodeValue
	}
}

var lists = new Map()
var revision = -1
var currentUser = null

function unsubscribe(user) {
	var xmlhttp = new XMLHttpRequest()
	xmlhttp.onreadystatechange = function() {
		if (this.readyState == 4 && this.status == 200) {
			refreshLists()
		}
	}
	xmlhttp.open("POST","/MuWire/Trust", true)
	xmlhttp.setRequestHeader('Content-type', 'application/x-www-form-urlencoded');
	xmlhttp.send("action=unsubscribe&persona=" + user)
}

function updateDiv(name, list) {
	
	var html = "<table><thead><tr><th>User</th><th>Reason</th><th>Your Trust</th></tr></thead><tbody>"
	
	var i
	for (i = 0; i < list.length; i++) {
		html += "<tr>"
		html += "<td>" + list[i].user + "</td>"
		html += "<td>" + list[i].reason + "</td>"  // maybe in <pre>
		html += "<td>" + list[i].status + "</td>"
		html += "</tr>"
	}
	
	document.getElementById(name).innerHTML = html
}

function parse(xmlNode, list) {
	var users = xmlNode.getElementsByTagName("Persona")
	var i
	for (i = 0; i < users.length; i++)
		list.push(new Persona(users[i]))
}

function displayList(user) {
	currentUser = user
	
	var currentList = lists.get(currentUser)
	
	var xmlhttp = new XMLHttpRequest()
	xmlhttp.onreadystatechange = function() {
		if (this.readyState == 4 && this.status == 200) {
			var trusted = []
			var distrusted = []
			
			var xmlNode = this.responseXML.getElementsByTagName("Trusted")[0]
			parse(xmlNode, trusted)
			xmlNode = this.responseXML.getElementsByTagName("Distrusted")[0]
			parse(xmlNode, distrusted)
			
			var currentListDiv = document.getElementById("currentList")
			currentListDiv.innerHTML = "Trust List Of " + user
		
			updateDiv("trusted", trusted)
			updateDiv("distrusted", distrusted)	
		}
	}
	xmlhttp.open("GET", "/MuWire/Trust?section=list&user=" + currentList.userB64)
	xmlhttp.send()
}

function refreshLists() {
	var xmlhttp = new XMLHttpRequest()
	xmlhttp.onreadystatechange = function() {
		if (this.readyState == 4 && this.status == 200) {
			lists.clear()
			var subs = this.responseXML.getElementsByTagName("Subscription")
			var i
			for (i = 0; i < subs.length; i++) {
				var trustList = new TrustList(subs[i])
				lists.set(trustList.user, trustList)				
			}
			
			
			var html = "<table><thead><tr><th>Name</th><th>Trusted</th><th>Distrusted</th><th>Status</th><th>Last Updated</th><th>Unsubscribe</th></tr></thead><tbody>"
			for (var [user, list] of lists) {
				html += "<tr>"
				html += "<td>" + "<a href='#' onclick='window.displayList(\"" + list.user + "\");return false;'>" + list.user + "</a></td>"
				html += "<td>" + list.trusted + "</td>"
				html += "<td>" + list.distrusted +"</td>"
				html += "<td>" + list.status + "</td>"
				html += "<td>" + list.timestamp + "</td>"
				html += "<td>" + "<a href='#' onclick='window.unsubscribe(\"" + list.userB64 + "\");return false;'>Unsubscribe</a></td>"
				html += "</tr>"
			}
			html += "</tbody></table>"
			
			document.getElementById("trustLists").innerHTML = html
			
			if (currentUser != null)
				displayUser(currentUser)
		}
	}
	xmlhttp.open("GET", "/MuWire/Trust?section=subscriptions", true)
	xmlhttp.send()
}

function fetchRevision() {
	var xmlhttp = new XMLHttpRequest()
	xmlhttp.onreadystatechange = function() {
		if (this.readyState == 4 && this.status == 200) {
			var xmlDoc = this.responseXML
			var newRevision = xmlDoc.childNodes[0].childNodes[0].nodeValue
			if (newRevision > revision) {
				revision = newRevision
				refreshLists()
			}
		}
	}
	xmlhttp.open("GET", "/MuWire/Trust?section=revision", true)
	xmlhttp.send()
}

function initTrustLists() {
	setTimeout(fetchRevision, 1)
	setInterval(fetchRevision, 3000)
}