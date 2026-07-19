/**
 * 处理拖拽上传
 */
	var isDragOver		= false;//拖拽触发点
	var fileNumber		= -1; //上传文件编号
	var fileNumberPex	= "zyFileUpload_"; //编号前缀
	var currUploadfile  = {}; //当前上传的文件对象
	
	var uploadQueue		= [];//上传队列集合
	var isUploading		= false;//是否正在上传
	
	var XHR = null;
	
(function(isSupportFileUpload){
	
	//不支持拖拽上传，或者 不支持FormData ，显示WiFi表示
	if(!isSupportFileUpload){
		setElementText(getDropMessageElement(), '您的浏览器不支持拖拽上传');
		return;
	//更换样式
	}else{
		setElementText(getDropMessageElement(), '请将图书或字体拖拽至此即可上传');
	}

	addEvent();
	
	/**
	 * 添加事件
	 */
	function addEvent(){
		var click = document.getElementById('click');
		var dropArea = document.getElementById('drag');
		click.addEventListener('change', handleDrop, false);
		dropArea.addEventListener('dragover', handleDragOver, false);
		dropArea.addEventListener('dragleave', handleDragLeave, false);
		dropArea.addEventListener('drop', handleDrop, false);
	} 

	/**
	 * 松开拖拽文件的处理，进行上传
	 */
	function handleDrop(evt){
		
		evt.stopPropagation();
		evt.preventDefault();

		removeElementClass(document.getElementById('drag'), 'active');
		
		isDragOver = false;
		
		var file={};
		var errorMsgs = [];

		var len = 0;
		if(typeof (this.files) == 'object'){
			len = this.files.length;
		}else{
			len = evt.dataTransfer.files.length;
		}


		for(var i=0; i < len; i++){
			fileNumber ++ ;

			if(typeof(this.files) == 'object'){
				file = this.files[i];
			}else{
				file = evt.dataTransfer.files[i];
			}

			//检测文件
			msg = checkFile(file);
			//文件可以通过
			if(!msg){
				file.id = fileNumberPex+fileNumber;

				//添加全局
				filesUpload.push(file);
				//添加上传队列
				uploadQueue.push(file);
				//在页面进行展示
				fileQueuedPC(file, 1);
			}else{
				errorMsgs.push(msg)
			}
		}

		if(errorMsgs.length>0){
			//只选择做一个进行上传
			if(len==1){
				alert(errorMsgs[0]);
				
			}else{
				alert("你选择了"+len+"个文件，只能上传"+(len - errorMsgs.length)+"个文件。\n请选择可支持文件格式且文件名不能重复。");
			}			
		}

		//拿出第一个，进行上传
		if(!isUploading && uploadQueue.length>0) uploadFiles(uploadQueue.shift());

		//清空input内容，防止两次上传文件一样，change事件不触发
		document.getElementById('click').value = '';

	}

	function handleDragOver(evt){
				
		evt.stopPropagation();
		evt.preventDefault();
		//防止多次DOM操作
		if (!isDragOver) {
			addElementClass(document.getElementById('drag'), 'active');
			isDragOver = true;
		}
		
		
	}
	
	function handleDragLeave(evt){
		
		evt.stopPropagation();
		evt.preventDefault();
		isDragOver = false;
		removeElementClass(document.getElementById('drag'), 'active');
	}



	function uploadFiles(file){
		//正在上传
		isUploading = true;
		//设置上传的数据
//        var reader = new FileReader();
//        reader.readAsDataURL(file);
//        reader.onload = function (e) {
//            var data = e.target.result;
            var fd = new FormData();
            fd.append("fileName", file.name);
            fd.append("fileData", file);
            //设置当前的上传对象
            currUploadfile = file;
            var request = new XMLHttpRequest();
            var finished = false;
            XHR = request;

            function finishUpload(success){
                if(finished){
                    return;
                }
                finished = true;
                if(success){
                    uploadSuccess(file, {}, request.status);
                }else{
                    uploadError(file);
                }
                nextUpload();
            }

            request.upload.addEventListener("progress", function(evt){
                uploadProgress(file, evt.loaded || evt.position, evt.total);
            }, false);
            request.upload.addEventListener("error", function(){
                finishUpload(false);
            }, false);
            request.upload.addEventListener("abort", function(){
                finishUpload(false);
            }, false);
            request.onreadystatechange = function() {
                if(request.readyState == 4){
                    finishUpload(request.status == 200);
                }
            };
            request.open("POST", config.url);
    //		XHR.setRequestHeader("Content-Type","application/octet-stream");
            request.send(fd);
//		};
	}
	
	//进行下一个上传
	function nextUpload(){
		isUploading = false;
		if(uploadQueue.length>0){
			 uploadFiles(uploadQueue.shift());		
		}else{
			 //没有正在上传的了
			 currUploadfile  = {}
		}
	}
	
	//上传出错误了，比如断网，
	function uploadError(file){
		//移除全局变量中的，上传出错的
		removeFileFromFilesUpload(filesUpload, file.id);
		var progressElement = fileMap[file.name];
		if(!progressElement){
			return;
		}
		removeElementClass(progressElement, 'red');
		addElementClass(progressElement, 'op_wrong');
		setElementText(progressElement, '');
	}
	
	
	//对外部注册的函数
	var HTML5Funs = {
		/**
		 * 取消上传
		 * @param string fid 文件的Id 
		 */
		cancelUpload : function(fid){
			
			var filesUploadKey = -1;
			var uploadQueueKey = -1;
			
			
			//从全局中删除文件
			removeFileFromFilesUpload(filesUpload, fid)
			
			//如果是正在上传的，AJAX取消
			if(currUploadfile.id == fid){
				XHR.abort();
			}else{
				//从上传队列中移除
				removeFileFromFilesUpload(uploadQueue, fid)
			}
		}
	};
	
	window.HTML5Funs = HTML5Funs;
	

})("FormData" in window && "ondrop" in document.body);
