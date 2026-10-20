import 'dart:io';
import 'dart:convert';
import 'dart:typed_data';
import 'detector.dart';
void main(List<String> args) {
 final input=jsonDecode(File(args[0]).readAsStringSync()) as Map<String,dynamic>;
 for(final f in input['fixtures']) {
  final gray=base64Decode(f['grayBase64']);
  final quad=detectQuadFromGrayscale(gray, f['width'], f['height']);
  f['expectedCorners']=quad?.points.map((p)=>[p.x,p.y]).toList();
 }
 File(args[1]).writeAsStringSync(const JsonEncoder.withIndent('  ').convert(input)+'\n');
}
