/**
 * 公共函数
 */
//全局的配置文件 
var config = {
	fileTypes: "txt|epub|umd|pdf|mobi|azw3|azw", //允许上传的文件格式 "txt|epub" // |doc|docx|wps|xls|xlsx|et|ppt|pptx|dps
	//url : "http://"+location.host+"?action=addBook",//"http://localhost/t/post.php",//
	url: "../addLocalBook",
	fileLimitSize : 500 * 1024 *1024

};

//文件对应序号
var fileMap = {};

/**
 * HTML5 和 flash 公用，所有文件对象集合
 * @var array
 */
var filesUpload	= []; //

function getUploadTableBody()
{
	return document.getElementById('drag').getElementsByTagName('tbody')[0];
}

function getUploadItemRows()
{
	var rows = getUploadTableBody().rows;
	var itemRows = [];
	for(var i = 0; i < rows.length; i++){
		if(rows[i].getAttribute('data-js') == 'item'){
			itemRows.push(rows[i]);
		}
	}
	return itemRows;
}

function createUploadItemRow()
{
	var row = document.createElement('tr');
	row.setAttribute('data-js', 'item');
	row.setAttribute('data-status', 'init');
	for(var i = 0; i < 4; i++){
		var cell = row.insertCell(-1);
		var span = document.createElement('span');
		cell.appendChild(span);
		if(i == 3){
			span.appendChild(document.createElement('i'));
		}
	}
	return row;
}

function setElementText(element, value)
{
	var text = value == null ? '' : String(value);
	if(typeof element.textContent != 'undefined'){
		element.textContent = text;
	}else{
		element.innerText = text;
	}
}

function addElementClass(element, className)
{
	if((" " + element.className + " ").indexOf(" " + className + " ") == -1){
		element.className += (element.className ? " " : "") + className;
	}
}

function removeElementClass(element, className)
{
	var classes = element.className.split(/\s+/);
	var remaining = [];
	for(var i = 0; i < classes.length; i++){
		if(classes[i] && classes[i] != className){
			remaining.push(classes[i]);
		}
	}
	element.className = remaining.join(' ');
}

function getDropMessageElement()
{
	var rows = getUploadTableBody().rows;
	var lastRow = rows[rows.length - 1];
	return lastRow.cells[0].getElementsByTagName('span')[0];
}

//初始化表格
init();

function init(){
	//判断浏览器的高度预留空表格
	var tr_num = parseInt(Math.round(((window.innerHeight || document.documentElement.clientHeight) *.5)/43));
	var tbody = getUploadTableBody();
	var firstRow = tbody.rows.length > 0 ? tbody.rows[0] : null;
	var i = 0;
	while(i < tr_num){
		tbody.insertBefore(createUploadItemRow(), firstRow);
		i ++;
	}
}

//统计文件大小
function countFileSize(fileSize)
{
	var KB  = 1024;
	var MB = 1024 * 1024;
	if(KB >= fileSize){
	   return fileSize+"B";
	}else if(MB >= fileSize){
		return (fileSize/KB).toFixed(2)+"KB";
	}else{
		return (fileSize/MB).toFixed(2)+"MB";
	}
}

//如果文件太长进行截取
function substr_string(name)
{
	var maxLen = 30;
	var len = name.length;
	if(len < maxLen )return name;

	var lastIndex = name.lastIndexOf(".");
	var suffix    = name.substr(lastIndex);
	var pre       = name.substr(0,lastIndex);
	var preLen    = pre.length;
	var preStart  = preLen - 20;
	//前面10个 + 后面5个
	var fileName  =  pre.substr(0,20) + "...." + pre.substr( preStart > 4 ? -4 : -preStart , 4)+suffix;
	return fileName
}


function checkFile(file) {
	if (!file.name || !file.name.toLowerCase().match('('+config.fileTypes+')$')) {
		return "格式不支持";
	}

	var len = filesUpload.length;
	for(var i=0; i< len; i++){
		if(filesUpload[i].name == file.name)	{
			return "文件已存在";
		}
	}
	return null;
}

/**
 * 添加文件时，回调的函数
 * @param object file 文件对象
 * @param int type 0 是swf 上传的，1 是html5上传的
 */
function fileQueuedPC(file, type)
{
	var size=0 ,fid=file.id, name="";
	type = type || 0;

	if(file != undefined )
	{
		//计算文件大小 单位MB
		size = countFileSize(file.size);
		name = substr_string(file.name);

		var itemRows = getUploadItemRows();
		var row = null;
		var i = 0;
		for(i = 0; i < itemRows.length; i++){
			if(itemRows[i].getAttribute('data-status') == 'init'){
				row = itemRows[i];
				break;
			}
		}
		if(row == null){
			var tbody = getUploadTableBody();
			row = createUploadItemRow();
			tbody.insertBefore(row, tbody.rows[tbody.rows.length - 1]);
			i = itemRows.length;
		}

		setElementText(row.cells[0].getElementsByTagName('span')[0], i + 1);
		setElementText(row.cells[1].getElementsByTagName('span')[0], name);
		setElementText(row.cells[2].getElementsByTagName('span')[0], size);
		var progressElement = row.cells[3].getElementsByTagName('i')[0];
		addElementClass(progressElement, 'red');
		setElementText(progressElement, '0%');
		row.setAttribute('data-status', 'ed');

		fileMap[file.name] = progressElement;

	}
}

//上传时返回的状态
function uploadProgress(file, bytesLoaded, bytesTotal)
{
	setElementText(fileMap[file.name], parseInt((bytesLoaded/bytesTotal)*100)+"%");
}


//上传成功
function uploadSuccess(file, serverData, res)
{
	var progressElement = fileMap[file.name];
	removeElementClass(progressElement, 'red');
	addElementClass(progressElement, 'op_right');
	setElementText(progressElement, '');
}

/**
 * 查找在数组中的位置
 */
function findObjectKey (object, fid){
	var len = object.length; 
	for(var i=0; i<len; i++){
		if(object[i].id == fid){
			return i;
		}
	}
	return -1;
}

/**
 * 从全局的文件集合中移除文件，一般上传失败时使用
 * @param array files   文件对象集合  [{},{},{}]
 * @param int fid  要删除的文件id
 * @return 删除后的数组，  其实数组是引用类型可以不返回
 */
function removeFileFromFilesUpload(files, fid){
	//console.log(currUploadfile);

	var filesUploadKey = -1;
	
	filesUploadKey = findObjectKey(files, fid);
	//从全局文件中移除
	if(filesUploadKey > -1)
		 files.splice(filesUploadKey, 1);

	return files;
}
