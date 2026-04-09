import easyocr
import fitz
import gc
import io
import numpy as np
import re
import requests
from PIL import Image
from flask import Flask, request, jsonify
from flask_cors import CORS

app = Flask(__name__)
CORS(app)

reader = easyocr.Reader(['en'], gpu=False)

def parse_vin_data(ocr_result):
    """解析 OCR 结果为结构化数据"""
    sorted_result = sorted(ocr_result, key=lambda x: (x[0][0][1] + x[0][2][1]) / 2)

    current_line = []
    last_y = -1
    y_threshold = 20
    merged_lines = []

    for item in sorted_result:
        box = item[0]
        y_center = (box[0][1] + box[2][1]) / 2
        x_left = box[0][0]
        text = item[1]

        if last_y == -1 or abs(y_center - last_y) < y_threshold:
            current_line.append({'text': text, 'x': x_left, 'y': y_center})
            last_y = y_center
        else:
            if current_line:
                current_line.sort(key=lambda x: x['x'])
                merged_lines.append(' '.join([x['text'] for x in current_line]))
            current_line = [{'text': text, 'x': x_left, 'y': y_center}]
            last_y = y_center

    if current_line:
        current_line.sort(key=lambda x: x['x'])
        merged_lines.append(' '.join([x['text'] for x in current_line]))

    print("合并后的行:")
    for line in merged_lines:
        print(f"  {line}")

    data = {
        'vin': None,
        'model': None,
        'engine_no': None,
        'exterior_color': None,
        'production_date': None,
        'status': None
    }

    for line in merged_lines:
        line = line.strip()
        if not line:
            continue
        match = re.match(r'^([a-zA-Z_]+)\s*[:：]\s*(.+)$', line)
        if match:
            key = match.group(1).strip().lower()
            value = match.group(2).strip()
            if key in data:
                data[key] = value
                print(f"  匹配字段: {key} = {value}")

    return data, '\n'.join(merged_lines)

def send_callback(callback_url, task_id, msg_type, data: dict):
    """ 统一回调方法
    对应Controller: POST /vehicle/info/callback
    Body 结构: { "task_id": "xxx", "type": "progress|complete|error", ...其他数据 }
    """
    payload = {
        'task_id': task_id,
        'type': msg_type,   # 对应 Controller 中的 data.get("type")
        **data              # 展开其他数据
    }
    try:
        # 统一回调地址：/vehicle/info/callback
        url = f"{callback_url}/vehicle/info/callback"
        print(f"回调 [{msg_type}] → {url}, payload: {payload}")
        resp = requests.post(url, json=payload, timeout=10)
        print(f"回调响应: {resp.status_code} {resp.text}")
    except Exception as e:
        print(f"回调失败 [{msg_type}]: {e}")

@app.route('/ocr/pdf', methods=['POST'])
def ocr_vin():
    """VIN 码识别接口"""
    pdf_document = None
    callback_url = None
    task_id = None

    try:
        file = request.files['file']
        callback_url = request.form.get('callback_url')
        task_id = request.form.get('task_id')

        print(f"收到请求, task_id: {task_id}, callback_url: {callback_url}")

        file_bytes = file.read()
        file_stream = io.BytesIO(file_bytes)

        pdf_document = fitz.open(stream=file_stream, filetype="pdf")
        total_pages = len(pdf_document)
        results = []

        # 发送初始进度
        send_callback(callback_url, task_id, 'progress', {
            'current': 0,
            'total': total_pages,
            'percent': 0,
            'message': f'开始处理，共{total_pages} 页'
        })

        for page_num in range(total_pages):
            print(f"\n===== 开始识别第 {page_num + 1} 页 =====")

            # 发送进度回调（统一接口）
            send_callback(callback_url, task_id, 'progress', {
                'current': page_num + 1,
                'total': total_pages,
                'percent': int((page_num + 1) / total_pages * 70) + 20,
                'message': f'正在识别第 {page_num + 1} / {total_pages} 页'
            })

            pix = None
            img = None
            img_array = None
            img_bytes = None
            ocr_result = None

            try:
                pix = pdf_document[page_num].get_pixmap(matrix=fitz.Matrix(3, 3))
                img_bytes = pix.tobytes("png")
                img = Image.open(io.BytesIO(img_bytes))
                img = img.convert('RGB')
                img_array = np.array(img)
                print(f"图片尺寸: {img_array.shape}")

                ocr_result = reader.readtext(img_array)
                print(f"原始识别条数: {len(ocr_result)}")

                parsed_data, raw_text = parse_vin_data(ocr_result)
                print(f"解析结果: {parsed_data}")

                results.append({
                    "page": page_num + 1,
                    "text": raw_text,
                    "data": parsed_data
                })

            finally:
                #释放内存
                if pix is not None: del pix
                if img is not None: del img
                if img_array is not None: del img_array
                if img_bytes is not None: del img_bytes
                if ocr_result is not None: del ocr_result
                gc.collect()

        # 发送完成回调（统一接口）
        print("\n===== 发送完成回调 =====")
        send_callback(callback_url, task_id, 'complete', {
            'results': results,
            'message': f'识别完成，共处理 {total_pages} 页'
        })

        return jsonify({
            'code': 200,
            'message': '处理完成',
            'data': results
        })

    except Exception as e:
        print(f"错误: {str(e)}")
        import traceback
        traceback.print_exc()

        # 发送错误回调（统一接口）
        if callback_url and task_id:
            send_callback(callback_url, task_id, 'error', {
                'message': str(e)
            })

        return jsonify({'code': 500, 'message': str(e)}), 500

    finally:
        if pdf_document is not None:
            pdf_document.close()
            del pdf_document
        gc.collect()
        print("资源释放完成")

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000, debug=True)