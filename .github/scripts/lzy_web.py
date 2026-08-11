import datetime
import os
import sys
import time

import requests

# Cookie 中 phpdisk_info 的值
cookie_phpdisk_info = os.environ.get('phpdisk_info')
# Cookie 中 ylogin 的值
cookie_ylogin = os.environ.get('ylogin')

# 请求头
headers = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/89.0.4389.72 Safari/537.36 Edg/89.0.774.45',
    'Accept-Language': 'zh-CN,zh;q=0.9',
    'Referer': 'https://accounts.woozooo.com/accounts.php?action=login&ref=pc.woozooo.com'
}

# 小饼干
cookie = {
    'ylogin': cookie_ylogin,
    'phpdisk_info': cookie_phpdisk_info
}


# 日志打印
def log(msg):
    china_time = datetime.datetime.now(
        datetime.timezone(datetime.timedelta(hours=8))
    )
    print(f"[{china_time.strftime('%Y.%m.%d %H:%M:%S')}] {msg}")


# 检查是否已登录
def login_by_cookie():
    url_account = "https://accounts.woozooo.com/accounts.php"
    if cookie['phpdisk_info'] is None:
        log('ERROR: 请指定 Cookie 中 phpdisk_info 的值！')
        return False
    if cookie['ylogin'] is None:
        log('ERROR: 请指定 Cookie 中 ylogin 的值！')
        return False
    res = requests.get(url_account, headers=headers, cookies=cookie, verify=True)
    if '网盘用户登录' in res.text:
        log('ERROR: 登录失败,请更新Cookie')
        return False
    else:
        log('登录成功')
        return True


# 上传文件
def upload_file(file_dir, folder_id):
    file_name = os.path.basename(file_dir)
    url_upload = "https://pc.woozooo.com/html5up.php"
    headers['Referer'] = f'https://pc.woozooo.com/mydisk.php?item=files&action=index&u={cookie_ylogin}'
    post_data = {
        'task': '1',
        'vie': '2',
        've': '2',
        'id': 'WU_FILE_2',
        "name": file_name,
        "folder_id_bb_n": folder_id,
    }
    for attempt in range(1, 4):
        log(f'开始第{attempt}次请求')
        try:
            with open(file_dir, "rb") as upload_stream:
                files = {
                    'upload_file': (file_name, upload_stream, 'application/octet-stream')
                }
                response = requests.post(
                    url_upload,
                    data=post_data,
                    files=files,
                    headers=headers,
                    cookies=cookie,
                    timeout=3600,
                )
            response.raise_for_status()
            log(f'response -> {response.text}')
            res = response.json()
            log(f"{file_dir} -> {res.get('info', '未知响应')}")
            if res.get('zt') == 1:
                return True
        except Exception as e:
            log(f'第{attempt}次请求异常: {e}')
        if attempt < 3:
            time.sleep(2)
    return False


# 上传文件夹内的文件
def upload_folder(folder_dir, folder_id):
    file_list = sorted(
        (
            os.path.join(root, file)
            for root, _, files in os.walk(folder_dir)
            for file in files
        ),
        reverse=True,
    )
    if not file_list:
        log('ERROR: 上传目录中没有文件')
        return False
    success = True
    for file in file_list:
        success = upload_file(file, folder_id) and success
    return success


# 上传
def upload(dir, folder_id):
    if not dir or not os.path.exists(dir):
        log('ERROR: 请指定有效的上传文件路径')
        return False
    if not folder_id:
        log('ERROR: 请指定蓝奏云的文件夹id')
        return False
    if os.path.isfile(dir):
        return upload_file(dir, str(folder_id))
    return upload_folder(dir, str(folder_id))


def main(argv):
    if len(argv) != 2:
        log('ERROR: 参数错误,请以这种格式重新尝试\npython lzy_web.py 需上传的路径 蓝奏云文件夹id')
        return 2
    if not login_by_cookie():
        return 1
    return 0 if upload(argv[0], argv[1]) else 1


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
