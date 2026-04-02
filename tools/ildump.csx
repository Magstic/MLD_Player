#r "C:\\Program Files (x86)\\Microsoft Visual Studio\\2019\\BuildTools\\MSBuild\\Current\\Bin\\Roslyn\\System.Collections.Immutable.dll"
#r "C:\\Program Files (x86)\\Microsoft Visual Studio\\2019\\BuildTools\\MSBuild\\Current\\Bin\\Roslyn\\System.Reflection.Metadata.dll"

using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Reflection.Emit;
using System.Reflection.Metadata;
using System.Reflection.Metadata.Ecma335;
using System.Reflection.PortableExecutable;

if (Args.Count < 3)
{
    Console.Error.WriteLine("Usage: ildump.csx <assembly-path> <type-name> <method-name>");
    return;
}

var assemblyPath = Path.GetFullPath(Args[0]);
var targetType = Args[1];
var targetMethod = Args[2];

var oneByte = new Dictionary<byte, OpCode>();
var twoByte = new Dictionary<byte, OpCode>();

foreach (var field in typeof(OpCodes).GetFields(BindingFlags.Public | BindingFlags.Static))
{
    if (!(field.GetValue(null) is OpCode opcode))
    {
        continue;
    }

    var value = unchecked((ushort)opcode.Value);
    if ((value & 0xFF00) == 0xFE00)
    {
        twoByte[(byte)(value & 0xFF)] = opcode;
    }
    else
    {
        oneByte[(byte)value] = opcode;
    }
}

using (var stream = File.OpenRead(assemblyPath))
using (var pe = new PEReader(stream))
{
    var metadata = pe.GetMetadataReader();

    foreach (var typeHandle in metadata.TypeDefinitions)
    {
        var type = metadata.GetTypeDefinition(typeHandle);
        var ns = metadata.GetString(type.Namespace);
        var name = metadata.GetString(type.Name);
        var fullName = string.IsNullOrEmpty(ns) ? name : ns + "." + name;

        if (!fullName.Equals(targetType, StringComparison.OrdinalIgnoreCase) &&
            !name.Equals(targetType, StringComparison.OrdinalIgnoreCase))
        {
            continue;
        }

        foreach (var methodHandle in type.GetMethods())
        {
            var method = metadata.GetMethodDefinition(methodHandle);
            var methodName = metadata.GetString(method.Name);
            if (!methodName.Equals(targetMethod, StringComparison.OrdinalIgnoreCase))
            {
                continue;
            }

            Console.WriteLine($"{fullName}::{methodName} RVA=0x{method.RelativeVirtualAddress:X}");

            if (method.RelativeVirtualAddress == 0)
            {
                Console.WriteLine("  <external or runtime-provided>");
                return;
            }

            var body = pe.GetMethodBody(method.RelativeVirtualAddress);
            var il = body.GetILBytes();
            var offset = 0;

            while (offset < il.Length)
            {
                var start = offset;
                OpCode opcode;
                var first = il[offset++];
                if (first == 0xFE)
                {
                    opcode = twoByte[il[offset++]];
                }
                else
                {
                    opcode = oneByte[first];
                }

                object operand = null;
                switch (opcode.OperandType)
                {
                    case OperandType.InlineNone:
                        break;
                    case OperandType.ShortInlineI:
                        operand = (sbyte)il[offset++];
                        break;
                    case OperandType.ShortInlineVar:
                        operand = il[offset++];
                        break;
                    case OperandType.InlineVar:
                        operand = BitConverter.ToUInt16(il, offset);
                        offset += 2;
                        break;
                    case OperandType.InlineI:
                    case OperandType.InlineBrTarget:
                        operand = BitConverter.ToInt32(il, offset);
                        offset += 4;
                        break;
                    case OperandType.ShortInlineBrTarget:
                        operand = (sbyte)il[offset++];
                        break;
                    case OperandType.InlineI8:
                        operand = BitConverter.ToInt64(il, offset);
                        offset += 8;
                        break;
                    case OperandType.ShortInlineR:
                        operand = BitConverter.ToSingle(il, offset);
                        offset += 4;
                        break;
                    case OperandType.InlineR:
                        operand = BitConverter.ToDouble(il, offset);
                        offset += 8;
                        break;
                    case OperandType.InlineString:
                        operand = ResolveString(metadata, BitConverter.ToInt32(il, offset));
                        offset += 4;
                        break;
                    case OperandType.InlineField:
                        operand = ResolveField(metadata, BitConverter.ToInt32(il, offset));
                        offset += 4;
                        break;
                    case OperandType.InlineMethod:
                        operand = ResolveMethod(metadata, BitConverter.ToInt32(il, offset));
                        offset += 4;
                        break;
                    case OperandType.InlineType:
                        operand = ResolveType(metadata, BitConverter.ToInt32(il, offset));
                        offset += 4;
                        break;
                    case OperandType.InlineTok:
                        operand = ResolveToken(metadata, BitConverter.ToInt32(il, offset));
                        offset += 4;
                        break;
                    case OperandType.InlineSig:
                        operand = "sig(0x" + BitConverter.ToInt32(il, offset).ToString("X") + ")";
                        offset += 4;
                        break;
                    case OperandType.InlineSwitch:
                        var count = BitConverter.ToInt32(il, offset);
                        offset += 4 + count * 4;
                        operand = "switch[" + count + "]";
                        break;
                    default:
                        operand = "unsupported";
                        break;
                }

                Console.WriteLine(
                    operand == null
                        ? $"  IL_{start:X4}: {opcode.Name}"
                        : $"  IL_{start:X4}: {opcode.Name} {operand}");
            }

            return;
        }
    }

    Console.Error.WriteLine("Method not found.");
}

string ResolveString(MetadataReader metadata, int token)
{
    return "\"" + metadata.GetUserString(MetadataTokens.UserStringHandle(token)) + "\"";
}

string ResolveField(MetadataReader metadata, int token)
{
    var handle = (EntityHandle)MetadataTokens.Handle(token);
    if (handle.Kind == HandleKind.FieldDefinition)
    {
        var field = metadata.GetFieldDefinition((FieldDefinitionHandle)handle);
        return metadata.GetString(field.Name);
    }

    if (handle.Kind == HandleKind.MemberReference)
    {
        var member = metadata.GetMemberReference((MemberReferenceHandle)handle);
        return metadata.GetString(member.Name);
    }

    return "field(0x" + token.ToString("X8") + ")";
}

string ResolveMethod(MetadataReader metadata, int token)
{
    var handle = (EntityHandle)MetadataTokens.Handle(token);
    if (handle.Kind == HandleKind.MethodDefinition)
    {
        var method = metadata.GetMethodDefinition((MethodDefinitionHandle)handle);
        return metadata.GetString(method.Name);
    }

    if (handle.Kind == HandleKind.MemberReference)
    {
        var member = metadata.GetMemberReference((MemberReferenceHandle)handle);
        return metadata.GetString(member.Name);
    }

    return "method(0x" + token.ToString("X8") + ")";
}

string ResolveType(MetadataReader metadata, int token)
{
    var handle = (EntityHandle)MetadataTokens.Handle(token);
    if (handle.Kind == HandleKind.TypeDefinition)
    {
        var type = metadata.GetTypeDefinition((TypeDefinitionHandle)handle);
        var ns = metadata.GetString(type.Namespace);
        var name = metadata.GetString(type.Name);
        return string.IsNullOrEmpty(ns) ? name : ns + "." + name;
    }

    if (handle.Kind == HandleKind.TypeReference)
    {
        var type = metadata.GetTypeReference((TypeReferenceHandle)handle);
        var ns = metadata.GetString(type.Namespace);
        var name = metadata.GetString(type.Name);
        return string.IsNullOrEmpty(ns) ? name : ns + "." + name;
    }

    return "type(0x" + token.ToString("X8") + ")";
}

string ResolveToken(MetadataReader metadata, int token)
{
    var handle = (EntityHandle)MetadataTokens.Handle(token);
    switch (handle.Kind)
    {
        case HandleKind.TypeDefinition:
        case HandleKind.TypeReference:
            return ResolveType(metadata, token);
        case HandleKind.MethodDefinition:
        case HandleKind.MemberReference:
            return ResolveMethod(metadata, token);
        case HandleKind.FieldDefinition:
            return ResolveField(metadata, token);
        default:
            return "token(0x" + token.ToString("X8") + ")";
    }
}
