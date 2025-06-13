# music player

Example-SoundPack(https://github.com/zoma1101/Example-SoundPack/tree/main)


状況に応じて切り替わるBGMを自由に設定できます。
使用方法は.minecraft内のsoundpacks内に以下のように設定すればＢＧＭを設定後、
pack_idと同じ名前のリソースパックを適用することでBGMが流れます。


条件ファイル
soundpack/pack_id/assets/music_player/conditions/predicate.json
OGGファイル
soundpack/pack_id/assets/pack_id/music/name.ogg

条件ファイルの設定方法
`predicate.json`
```
{
"priority": 100, #（値が高いほど複数の条件を満たしているとき優先して再生されます）
"music": "music/name.ogg",
再生条件
}
```

再生条件
```
"biomes":[] #指定したバイオーム内で再生されます。biometag可
"is_night":true / false #trueだと夜にだけ再生されます。
"is_combat":true / false #trueだと戦闘中に再生されます。正確に言えば付近のモブが攻撃準備をしていると再生されます。
"isVillage":true / false #trueだと村人が付近にいるときに再生されます。
"min_y": n #nより高いところにいると再生されます。
"max_y": n #nより低いところにいると再生されます。
"weather" : [clear/rain/thunder] #指定した天候のときに再生されます。
"dimensions" : [] #指定したディメンションのときに再生されます。
"gui_screen": [
crafting/inventory/furnace/brewing_stand/chest/creative
]
#指定したGUIを開いているときのみ再生されます。


"entity_conditions": [] #指定したエンティティがradiusブロック内にmin_count以上、max_count以下いるとき再生されます。
"radius": n, #nブロック内にいるか検知します
"min_count": n, 
"max_count": n
```
