"""把训练好的分类器导出为 ONNX，验证导出链路（分类器形态：64x64 -> 类别）。"""
import json, torch, torch.nn as nn, torch.nn.functional as F, onnx
BASE='/root/盘点/train'
cls=json.load(open(BASE+'/real_classes.json',encoding='utf-8'))
N=cls['classes']
class Net(nn.Module):
    def __init__(self,n):
        super().__init__()
        self.c1=nn.Conv2d(3,24,3,padding=1); self.c2=nn.Conv2d(24,48,3,padding=1)
        self.c3=nn.Conv2d(48,64,3,padding=1); self.fc=nn.Linear(64*8*8,n)
    def forward(self,x):
        x=F.relu(F.max_pool2d(self.c1(x),2)); x=F.relu(F.max_pool2d(self.c2(x),2))
        x=F.relu(F.max_pool2d(self.c3(x),2))
        return self.fc(x.reshape(x.size(0),-1))
net=Net(N); net.load_state_dict(torch.load(BASE+'/net_real_final.pt',map_location='cpu')); net.eval()
dummy=torch.randn(1,3,64,64)
torch.onnx.export(net,dummy,BASE+'/classifier_real.onnx',
    input_names=['input'],output_names=['logits'],opset_version=13)
m=onnx.load(BASE+'/classifier_real.onnx')
print('导出成功 classifier_real.onnx')
print('input:',[(i.name,[d.dim_value for d in i.type.tensor_type.shape.dim]) for i in m.graph.input])
print('output:',[(o.name,[d.dim_value for d in o.type.tensor_type.shape.dim]) for o in m.graph.output])
print('size:',__import__('os').path.getsize(BASE+'/classifier_real.onnx'),'bytes')
